package net.webtide.cluster.common;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import net.webtide.cluster.common.util.SerializableSupplier;

public class JvmSettings implements Serializable
{
    private final SerializableSupplier<Jvm> jvmSupplier;
    private final List<String> opts;

    public JvmSettings(SerializableSupplier<Jvm> jvmSupplier, String... opts)
    {
        this.jvmSupplier = jvmSupplier;
        this.opts = Arrays.asList(opts);
    }

    public Jvm jvm()
    {
        return jvmSupplier.get();
    }

    public List<String> getOpts()
    {
        return opts;
    }
}
