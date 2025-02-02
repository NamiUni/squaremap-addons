package xyz.jpenilla.squaremap.addon.common.config;

import io.leangen.geantyref.TypeFactory;
import java.awt.Color;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;

import static java.util.Objects.requireNonNull;

@SuppressWarnings("unused")
public abstract class Config<C extends Config<C, W>, W extends WorldConfig> {
    private static final String CONFIG_FILE_NAME = "config.yml";

    private final Class<C> configClass;
    private final Path configFile;
    private final @Nullable Class<W> worldConfigClass;
    private final @Nullable Map<WorldIdentifier, W> worldConfigs;
    ConfigurationNode config;
    //static int VERSION;

    protected Config(final Class<C> configClass, final Plugin plugin) {
        this(configClass, null, plugin);
    }

    protected Config(
        final Class<C> configClass,
        final @Nullable Class<W> worldConfigClass,
        final Plugin plugin
    ) {
        this.configClass = configClass;
        this.worldConfigClass = worldConfigClass;
        this.worldConfigs = worldConfigClass == null ? null : new ConcurrentHashMap<>();
        this.configFile = plugin.getDataFolder().toPath().resolve(CONFIG_FILE_NAME);
    }

    public final void reload() {
        final YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(this.configFile)
            .nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(options -> options.serializers(builder -> builder.register(ColorSerializer.INSTANCE)))
            .build();

        try {
            this.config = loader.load();
        } catch (IOException ex) {
            throw new RuntimeException("Could not load config.yml, exception occurred (are there syntax errors?)", ex);
        }
        //this.config.options().copyDefaults(true);

        //VERSION = getInt("config-version", 1);
        //set("config-version", 1);

        this.readConfig(this.configClass, this);

        if (this.worldConfigs != null) {
            final Set<WorldIdentifier> removed = new HashSet<>(this.worldConfigs.keySet());
            this.worldConfigs.clear();

            if (removed.isEmpty()) {
                this.createWorldConfig("fake-world-999999999999999"); // load default section (dum but works)
            } else {
                // re-load previously loaded
                for (final WorldIdentifier identifier : removed) {
                    this.worldConfig(identifier);
                }
            }
        }

        try {
            loader.save(this.config);
        } catch (IOException ex) {
            throw new RuntimeException("Could not save " + this.configFile, ex);
        }
    }

    public final W worldConfig(final WorldIdentifier identifier) {
        if (this.worldConfigs == null || this.worldConfigClass == null) {
            throw new IllegalArgumentException("No world config");
        }

        return this.worldConfigs.computeIfAbsent(identifier, id -> {
            final String name = SquaremapProvider.get().getWorldIfEnabled(identifier)
                .map(MapWorld::name) // use names for now
                .orElseThrow();

            return this.createWorldConfig(name);
        });
    }

    private W createWorldConfig(String id) {
        if (this.worldConfigs == null || this.worldConfigClass == null) {
            throw new IllegalArgumentException("No world config");
        }

        try {
            final Constructor<W> ctr = this.worldConfigClass.getDeclaredConstructor(Config.class, String.class);
            ctr.setAccessible(true);
            final W worldConfig = ctr.newInstance(this, id);
            this.readConfig(this.worldConfigClass, worldConfig);
            return worldConfig;
        } catch (final ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void readConfig(Class<?> clazz, Object instance) {
        for (final Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isPrivate(method.getModifiers()) || method.getParameterTypes().length != 0 || method.getReturnType() != Void.TYPE) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(instance);
            } catch (InvocationTargetException ex) {
                throw new RuntimeException("Error invoking " + method, ex.getCause());
            } catch (Exception ex) {
                throw new RuntimeException("Error invoking " + method, ex);
            }
        }
    }

    private ConfigurationNode node(String path) {
        return this.config.node(splitPath(path));
    }

    protected final String getString(String path, String def) {
        return this.node(path).getString(def);
    }

    protected final boolean getBoolean(String path, boolean def) {
        return this.node(path).getBoolean(def);
    }

    protected final int getInt(String path, int def) {
        return this.node(path).getInt(def);
    }

    protected final double getDouble(String path, double def) {
        return this.node(path).getDouble(def);
    }

    protected final <T> List<T> getList(Class<T> elementType, String path, List<T> def) {
        try {
            return this.getList0(elementType, path, def);
            //return this.config.node((Object[]) splitPath(path)).getList(elementType, def); // turns empty list -> def
        } catch (SerializationException e) {
            throw rethrow(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <V> List<V> getList0(Class<V> elementType, final String path, List<V> def) throws SerializationException {
        final ConfigurationNode node = this.node(path);
        final Type type = TypeFactory.parameterizedClass(List.class, elementType);
        final @Nullable List<V> ret = node.virtual() ? null : (List<V>) node.get(type/*, def*/);
        return ret == null ? storeDefault(node, type, def) : ret;
    }

    private static <V> V storeDefault(final ConfigurationNode node, final Type type, final V defValue) throws SerializationException {
        requireNonNull(defValue, "defValue");
        if (node.options().shouldCopyDefaults()) {
            node.set(type, defValue);
        }
        return defValue;
    }

    protected final Color getColor(String path, Color def) {
        try {
            return this.node(path).get(Color.class, def);
        } catch (SerializationException e) {
            throw rethrow(e);
        }
    }

    static Object[] splitPath(final String path) {
        final String[] split = path.split("\\.");
        for (int i = 0; i < split.length; i++) {
            final String s = split[i];
            if (s.contains(WorldConfig.DOT)) {
                split[i] = s.replace(WorldConfig.DOT, ".");
            }
        }
        return split;
    }

    @SuppressWarnings("unchecked")
    static <X extends Throwable> RuntimeException rethrow(final Throwable t) throws X {
        throw (X) t;
    }
}
