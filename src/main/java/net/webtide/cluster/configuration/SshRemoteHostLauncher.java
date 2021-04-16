//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package net.webtide.cluster.configuration;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder;
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.FileSystemFile;
import net.schmizz.sshj.xfer.LocalSourceFile;
import net.webtide.cluster.rpc.NodeProcess;
import net.webtide.cluster.util.IOUtil;

public class SshRemoteHostLauncher implements HostLauncher, JvmDependent
{
    private final Map<String, RemoteNodeHolder> nodes = new HashMap<>();
    private Jvm jvm;

    @Override
    public void close()
    {
        nodes.values().forEach(IOUtil::close);
        nodes.clear();
    }

    @Override
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public SshRemoteHostLauncher jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }

    @Override
    public void launch(String hostname, String hostId, String rendezVous) throws Exception
    {
        if (nodes.containsKey(hostname))
            throw new IllegalArgumentException("ssh launcher already launched node on host " + hostname);

        SSHClient sshClient = new SSHClient();
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        sshClient.loadKnownHosts();
        sshClient.connect(hostname);

        // key auth
        sshClient.authPublickey(System.getProperty("user.name"));

        // try remote port forwarding
        String remoteConnectString;
        try
        {
            int zkPort = Integer.parseInt(rendezVous.split(":")[1]);
            RemotePortForwarder.Forward forward = sshClient.getRemotePortForwarder().bind(
                new RemotePortForwarder.Forward(0), // remote port, dynamically choose one
                new SocketForwardingConnectListener(new InetSocketAddress("localhost", zkPort))
            );
            remoteConnectString = "localhost:" + forward.getPort();
        }
        catch (Exception e)
        {
            // remote port forwarding failed, try direct TCP connection
            remoteConnectString = rendezVous;
        }

        List<String> remoteClasspathEntries = new ArrayList<>();
        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        try (SFTPClient sftpClient = sshClient.newStatefulSFTPClient())
        {
            for (String classpathEntry : classpathEntries)
            {
                File cpFile = new File(classpathEntry);
                remoteClasspathEntries.add(".wtc/" + hostId + "/lib/" + cpFile.getName());
                if (!cpFile.isDirectory())
                    copyFile(sftpClient, hostId, cpFile.getName(), new FileSystemFile(cpFile));
                else
                    copyDir(sftpClient, hostId, cpFile, 1);
            }
        }
        String remoteClasspath = String.join(":", remoteClasspathEntries);
        String cmdLine = String.join(" ", buildCommandLine(jvm, remoteClasspath, hostId, remoteConnectString));

        Session session = sshClient.startSession();
        session.allocateDefaultPTY();
        Session.Command cmd = session.exec(cmdLine);
        new StreamCopier(cmd.getInputStream(), System.out, net.schmizz.sshj.common.LoggerFactory.DEFAULT)
            .bufSize(1)
            .spawnDaemon("stdout-" + hostname);
        new StreamCopier(cmd.getErrorStream(), System.err, net.schmizz.sshj.common.LoggerFactory.DEFAULT)
            .bufSize(1)
            .spawnDaemon("stderr-" + hostname);

        HashMap<String, Object> env = new HashMap<>();
        env.put(SFTPClient.class.getName(), sshClient.newStatefulSFTPClient());
        FileSystem fileSystem = FileSystems.newFileSystem(URI.create("wtc:" + hostId), env);

        RemoteNodeHolder remoteNodeHolder = new RemoteNodeHolder(hostId, fileSystem, sshClient, session, cmd);
        nodes.put(hostname, remoteNodeHolder);
    }

    private static List<String> buildCommandLine(Jvm jvm, String remoteClasspath, String nodeId, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable());
        cmdLine.addAll(jvm.getOpts());
        cmdLine.add("-classpath");
        cmdLine.add(remoteClasspath);
        cmdLine.add(NodeProcess.class.getName());
        cmdLine.add(nodeId);
        cmdLine.add(connectString);
        return cmdLine;
    }

    private void copyFile(SFTPClient sftpClient, String hostId, String filename, LocalSourceFile localSourceFile) throws Exception
    {
        String destFilename = ".wtc/" + hostId + "/lib/" + filename;
        String parentFilename = destFilename.substring(0, destFilename.lastIndexOf('/'));

        sftpClient.mkdirs(parentFilename);
        sftpClient.put(localSourceFile, destFilename);
    }

    private void copyDir(SFTPClient sftpClient, String hostId, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (!file.isDirectory())
            {
                String filename = file.getName();
                File currentFile = file;
                for (int i = 0; i < depth; i++)
                {
                    currentFile = currentFile.getParentFile();
                    filename = currentFile.getName() + "/" + filename;
                }
                copyFile(sftpClient, hostId, filename, new FileSystemFile(file));
            }
            else
            {
                copyDir(sftpClient, hostId, file, depth + 1);
            }
        }
    }

    private static class RemoteNodeHolder implements AutoCloseable {
        private final String hostId;
        private final FileSystem fileSystem;
        private final SSHClient sshClient;
        private final Session session;
        private final Session.Command command;

        private RemoteNodeHolder(String hostId, FileSystem fileSystem, SSHClient sshClient, Session session, Session.Command command) {
            this.hostId = hostId;
            this.fileSystem = fileSystem;
            this.sshClient = sshClient;
            this.session = session;
            this.command = command;
        }

        @Override
        public void close() throws Exception
        {
            IOUtil.close(fileSystem);

            // 0x03 is the character for CTRL-C -> send it to the remote PTY
            session.getOutputStream().write(0x03);
            // also send TERM signal
            command.signal(Signal.TERM);
            try
            {
                command.join(10, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                // timeout, ignore.
            }
            command.close();
            try (Session session = sshClient.startSession())
            {
                String folderName = ".wtc/" + hostId;
                try (Session.Command cmd = session.exec("rm -fr \"" + folderName + "\""))
                {
                    cmd.join();
                }
            }
            IOUtil.close(command);
            IOUtil.close(session);
            IOUtil.close(sshClient);
        }
    }
}
