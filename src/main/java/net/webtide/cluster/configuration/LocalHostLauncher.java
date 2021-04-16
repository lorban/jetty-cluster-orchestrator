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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import net.webtide.cluster.rpc.NodeProcess;
import net.webtide.cluster.util.IOUtil;

public class LocalHostLauncher implements HostLauncher
{
    public static final String HOSTNAME = "localhost";

    private Thread thread;

    @Override
    public void launch(String hostname, String hostId, String connectString) throws Exception
    {
        if (!"localhost".equals(hostname))
            throw new IllegalArgumentException("local launcher can only work with 'localhost' hostname");
        if (thread != null)
            throw new IllegalStateException("local launcher already spawned 'localhost' thread");

        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        for (String classpathEntry : classpathEntries)
        {
            File cpFile = new File(classpathEntry);
            if (!cpFile.isDirectory())
            {
                String filename = cpFile.getName();
                try (InputStream is = new FileInputStream(cpFile))
                {
                    copyFile(hostId, filename, is);
                }
            }
            else
            {
                copyDir(hostId, cpFile, 1);
            }
        }

        try
        {
            this.thread = NodeProcess.spawnThread(hostId, connectString);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws Exception
    {
        if (thread != null)
        {
            thread.interrupt();
            thread.join();
            thread = null;
        }
    }

    public static File rootPathOf(String hostId)
    {
        return new File(System.getProperty("user.home") + "/.wtc/" + hostId);
    }

    private void copyFile(String hostId, String filename, InputStream contents) throws Exception
    {
        File rootPath = rootPathOf(hostId);
        File libPath = new File(rootPath, "lib");

        File file = new File(libPath, filename);
        file.getParentFile().mkdirs();
        try (OutputStream fos = new FileOutputStream(file))
        {
            IOUtil.copy(contents, fos);
        }
    }

    private void copyDir(String hostId, File cpFile, int depth) throws Exception
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
                try (InputStream is = new FileInputStream(file))
                {
                    copyFile(hostId, filename, is);
                }
            }
            else
            {
                copyDir(hostId, file, depth + 1);
            }
        }
    }
}
