package net.webtide.cluster;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.webtide.cluster.common.JvmSettings;
import net.webtide.cluster.common.util.CommandLineUtil;
import net.webtide.cluster.common.util.IOUtil;

public class SshRemoteHostLauncher implements RemoteHostLauncher
{
    private final Map<String, Process> nodeProcesses = new HashMap<>();
    private final JvmSettings jvmSettings;

    public SshRemoteHostLauncher(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
    }

    @Override
    public void close()
    {
        for (Process process : nodeProcesses.values())
        {
            process.destroy();
            try
            {
                process.waitFor();
            }
            catch (InterruptedException e)
            {
                // ignore
            }
        }
        nodeProcesses.clear();
    }

    @Override
    public void launch(String hostname, String connectString) throws Exception
    {
        if (nodeProcesses.containsKey(hostname))
            return;

        String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        for (String classpathEntry : classpathEntries)
        {
            File cpFile = new File(classpathEntry);
            if (!cpFile.isDirectory())
            {
                String filename = cpFile.getName();
                try (InputStream is = new FileInputStream(cpFile))
                {
                    uploadFile(hostname, filename, is);
                }
            }
            else
            {
                uploadDir(hostname, cpFile, 1);
            }
        }

        try
        {
            List<String> cmdLine = CommandLineUtil.remoteNodeCommandLine(jvmSettings, CommandLineUtil.defaultLibPath(hostname), hostname, connectString);
            Process process = new ProcessBuilder(cmdLine).inheritIO().start();
            nodeProcesses.put(hostname, process);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }


    private void uploadFile(String hostname, String filename, InputStream contents) throws Exception
    {
        File rootPath = new File(System.getProperty("user.home") + "/.wtc/" + hostname);
        File libPath = new File(rootPath, "lib");

        File file = new File(libPath, filename);
        file.getParentFile().mkdirs();
        try (OutputStream fos = new FileOutputStream(file))
        {
            IOUtil.copy(contents, fos);
        }
    }

    private void uploadDir(String hostname, File cpFile, int depth) throws Exception
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
                    uploadFile(hostname, filename, is);
                }
            }
            else
            {
                uploadDir(hostname, file, depth + 1);
            }
        }
    }
}
