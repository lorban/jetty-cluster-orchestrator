package net.webtide.cluster.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.webtide.cluster.configuration.JvmSettings;
import net.webtide.cluster.rpc.RemoteNode;

public class CommandLineUtil
{
    public static File defaultRootPath(String hostname)
    {
        return new File(System.getProperty("user.home") + "/.wtc/" + hostname);
    }

    public static File defaultLibPath(String hostname)
    {
        File rootPath = defaultRootPath(hostname);
        return new File(rootPath, "lib");
    }

    public static List<String> remoteNodeCommandLine(JvmSettings jvmSettings, File libPath, String hostname, String connectString)
    {
        List<String> cmdLine = new ArrayList<>();
        String jvmHome = jvmSettings.jvm().getHome();
        if (jvmHome != null)
            cmdLine.add(jvmHome + "/bin/java");
        else
            cmdLine.add("java");
        cmdLine.addAll(jvmSettings.getOpts());
        cmdLine.add("-classpath");
        cmdLine.add(buildClassPath(libPath));
        cmdLine.add(RemoteNode.class.getName());
        cmdLine.add(hostname);
        cmdLine.add(connectString);
        return cmdLine;
    }

    private static String buildClassPath(File libPath)
    {
        File[] entries = libPath.listFiles();
        StringBuilder sb = new StringBuilder();
        for (File entry : entries)
        {
            sb.append(entry.getPath()).append(File.pathSeparatorChar);
        }
        if (sb.length() > 0)
            sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }
}
