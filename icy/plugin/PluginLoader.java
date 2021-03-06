/*
 * Copyright 2010, 2011 Institut Pasteur.
 * 
 * This file is part of ICY.
 * 
 * ICY is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ICY is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ICY. If not, see <http://www.gnu.org/licenses/>.
 */
package icy.plugin;

import icy.common.EventHierarchicalChecker;
import icy.common.UpdateEventHandler;
import icy.common.listener.ChangeListener;
import icy.main.Icy;
import icy.plugin.PluginDescriptor.PluginIdent;
import icy.plugin.PluginDescriptor.PluginNameSorter;
import icy.plugin.abstract_.Plugin;
import icy.plugin.interface_.PluginBundled;
import icy.plugin.interface_.PluginDaemon;
import icy.preferences.PluginPreferences;
import icy.system.IcyExceptionHandler;
import icy.system.thread.SingleProcessor;
import icy.system.thread.ThreadUtil;
import icy.util.ClassUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.swing.event.EventListenerList;

import org.xeustechnologies.jcl.JarClassLoader;

/**
 * Plugin Loader class.<br>
 * This class is used to load plugins from "plugins" package and "plugins" directory
 * 
 * @author Stephane<br>
 */
public class PluginLoader implements ChangeListener
{
    public static class PluginClassLoader extends JarClassLoader
    {
        public PluginClassLoader()
        {
            super();
        }

        /**
         * Give access to this method
         */
        public Class<?> getLoadedClass(String name)
        {
            return super.findLoadedClass(name);
        }

        /**
         * Give access to this method
         */
        public boolean isLoadedClass(String name)
        {
            return getLoadedClass(name) != null;
        }
    }

    public static interface PluginLoaderListener extends EventListener
    {
        public void pluginLoaderChanged(PluginLoaderEvent e);
    }

    public static class PluginLoaderEvent implements EventHierarchicalChecker
    {
        public PluginLoaderEvent()
        {
            super();
        }

        @Override
        public boolean isEventRedundantWith(EventHierarchicalChecker event)
        {
            return (event instanceof PluginLoaderEvent);
        }
    }

    public final static String PLUGIN_PACKAGE = "plugins";
    public final static String PLUGIN_PATH = "plugins";

    /**
     * static class
     */
    private static final PluginLoader instance = new PluginLoader();

    /**
     * class loader
     */
    private ClassLoader loader;
    /**
     * active daemons plugins
     */
    private ArrayList<PluginDaemon> activeDaemons;
    /**
     * Loaded plugin list
     */
    private ArrayList<PluginDescriptor> plugins;

    /**
     * internal updater (no need to dispatch event on the AWT thread)
     */
    private final UpdateEventHandler updater;
    /**
     * listeners
     */
    private final EventListenerList listeners;

    /**
     * JAR Class Loader disabled flag
     */
    protected boolean JCLDisabled;

    /**
     * internals
     */
    private final Runnable reloader;
    final SingleProcessor processor;

    private boolean initialized;
    private boolean loading;
    private boolean needReload;
    private boolean logError;

    /**
     * static class
     */
    private PluginLoader()
    {
        super();

        // default class loader
        loader = new PluginClassLoader();
        // active daemons
        activeDaemons = new ArrayList<PluginDaemon>();

        JCLDisabled = false;
        initialized = false;
        loading = false;
        needReload = false;
        logError = true;

        plugins = new ArrayList<PluginDescriptor>();
        listeners = new EventListenerList();

        // change event
        updater = new UpdateEventHandler(this);

        // reloader
        reloader = new Runnable()
        {
            @Override
            public void run()
            {
                reloadInternal();
            }
        };

        processor = new SingleProcessor(true, "Local Plugin Loader");
        // we want the processor to stay alive
        processor.setKeepAliveTime(1, TimeUnit.DAYS);

        // don't load by default as we need Preferences to be ready first
    };

    static void prepare()
    {
        if (!instance.initialized)
        {
            if (isLoading())
                waitWhileLoading();
            else
                reload();
        }
    }

    /**
     * Reload the list of installed plugins (asynchronous version).
     */
    public static void reloadAsynch()
    {
        if (isUpdating())
            instance.needReload = true;
        else
            instance.processor.addTask(instance.reloader);
    }

    /**
     * Reload the list of installed plugins (wait for completion).
     */
    public static void reload()
    {
        instance.processor.addTask(instance.reloader);
        waitWhileLoading();
    }

    /**
     * @deprecated USes {@link #reload()} instead.
     */
    @Deprecated
    public static void reload(boolean forceNow)
    {
        reload();
    }

    /**
     * Stop and restart all daemons plugins.
     */
    public static synchronized void resetDaemons()
    {
        // reset will be done later
        if (isLoading())
            return;

        stopDaemons();
        startDaemons();
    }

    /**
     * Reload the list of installed plugins (in "plugins" directory)
     */
    void reloadInternal()
    {
        needReload = false;
        loading = true;

        // stop daemon plugins
        stopDaemons();

        // reset plugins and loader
        final ArrayList<PluginDescriptor> newPlugins = new ArrayList<PluginDescriptor>();
        final ClassLoader newLoader;

        // special case where JCL is disabled
        if (JCLDisabled)
            newLoader = PluginLoader.class.getClassLoader();
        else
        {
            newLoader = new PluginClassLoader();

            // reload plugins directory to search path
            ((PluginClassLoader) newLoader).add(PLUGIN_PATH);
        }

        // no need to complete loading...
        if (processor.hasWaitingTasks())
            return;

        final HashSet<String> classes = new HashSet<String>();

        try
        {
            // search for plugins in "Plugins" package (needed when working from JAR archive)
            ClassUtil.findClassNamesInPackage(PLUGIN_PACKAGE, true, classes);
            // search for plugins in "Plugins" directory with default plugin package name
            ClassUtil.findClassNamesInPath(PLUGIN_PATH, PLUGIN_PACKAGE, true, classes);
        }
        catch (IOException e)
        {
            if (logError)
            {
                System.err.println("Error loading plugins :");
                IcyExceptionHandler.showErrorMessage(e, true);
            }
        }

        for (String className : classes)
        {
            // no need to complete loading...
            if (processor.hasWaitingTasks())
                return;

            try
            {
                // try to load class and check we have a Plugin class at same time
                final Class<? extends Plugin> pluginClass = newLoader.loadClass(className).asSubclass(Plugin.class);

                // // ignore interface or abstract classes
                // if ((!pluginClass.isInterface()) && (!ClassUtil.isAbstract(pluginClass)))
                // plugins.add(new PluginDescriptor(pluginClass));

                newPlugins.add(new PluginDescriptor(pluginClass));
            }
            catch (NoClassDefFoundError e)
            {
                if (logError)
                {
                    // fatal error
                    System.err.println("Class '" + className + "' cannot be loaded :");
                    System.err.println("Required class '" + ClassUtil.getQualifiedNameFromPath(e.getMessage())
                            + "' not found.");
                }
            }
            catch (OutOfMemoryError e)
            {
                // fatal error
                IcyExceptionHandler.showErrorMessage(e, false);
                System.err.println("Class '" + className + "' is discarded");
            }
            catch (Error e)
            {
                if (logError)
                {
                    // fatal error
                    IcyExceptionHandler.showErrorMessage(e, false);
                    System.err.println("Class '" + className + "' is discarded");
                }
            }
            catch (ClassCastException e)
            {
                // ignore ClassCastException (for classes which doesn't extend Plugin)
            }
            catch (ClassNotFoundException e)
            {
                // ignore ClassNotFoundException (for no public classes)
            }
            catch (Exception e)
            {
                // fatal error
                if (logError)
                {
                    IcyExceptionHandler.showErrorMessage(e, false);
                    System.err.println("Class '" + className + "' is discarded");
                }
            }
        }

        // sort list
        Collections.sort(newPlugins, PluginNameSorter.instance);

        loader = newLoader;
        plugins = newPlugins;

        loading = false;

        // notify change
        changed();
    }

    /**
     * Returns the list of daemon type plugins.
     */
    public static ArrayList<PluginDescriptor> getDaemonPlugins()
    {
        final ArrayList<PluginDescriptor> result = new ArrayList<PluginDescriptor>();

        synchronized (instance.plugins)
        {
            for (PluginDescriptor pluginDescriptor : instance.plugins)
            {
                final Class<? extends Plugin> classPlug = pluginDescriptor.getPluginClass();

                if ((classPlug != null) && PluginDaemon.class.isAssignableFrom(classPlug))
                {
                    // accept class ?
                    if (!ClassUtil.isAbstract(classPlug) && !classPlug.isInterface())
                        result.add(pluginDescriptor);
                }
            }
        }

        return result;
    }

    /**
     * Returns the list of active daemon plugins.
     */
    public static ArrayList<PluginDaemon> getActiveDaemons()
    {
        synchronized (instance.activeDaemons)
        {
            return new ArrayList<PluginDaemon>(instance.activeDaemons);
        }
    }

    /**
     * Start daemons plugins.
     */
    static synchronized void startDaemons()
    {
        // at this point active daemons should be empty !
        if (!instance.activeDaemons.isEmpty())
            stopDaemons();

        final ArrayList<String> inactives = PluginPreferences.getInactiveDaemons();
        final ArrayList<PluginDaemon> newDaemons = new ArrayList<PluginDaemon>();

        for (PluginDescriptor pluginDesc : getDaemonPlugins())
        {
            // not found in inactives ?
            if (inactives.indexOf(pluginDesc.getClassName()) == -1)
            {
                try
                {
                    final PluginDaemon plugin = (PluginDaemon) pluginDesc.getPluginClass().newInstance();
                    final Thread thread = new Thread(plugin, pluginDesc.getName());

                    thread.setName(pluginDesc.getName());
                    // so icy can exit even with running daemon plugin
                    thread.setDaemon(true);

                    // init daemon
                    plugin.init();
                    // start daemon
                    thread.start();
                    // register daemon plugin (so we can stop it later)
                    Icy.getMainInterface().registerPlugin((Plugin) plugin);

                    // add daemon plugin to list
                    newDaemons.add(plugin);
                }
                catch (Throwable t)
                {
                    IcyExceptionHandler.handleException(pluginDesc, t, true);
                }
            }
        }

        instance.activeDaemons = newDaemons;
    }

    /**
     * Stop daemons plugins.
     */
    public synchronized static void stopDaemons()
    {
        for (PluginDaemon daemonPlug : getActiveDaemons())
        {
            try
            {
                // stop the daemon
                daemonPlug.stop();
            }
            catch (Throwable t)
            {
                IcyExceptionHandler.handleException(((Plugin) daemonPlug).getDescriptor(), t, true);
            }
        }

        // no more active daemons
        instance.activeDaemons = new ArrayList<PluginDaemon>();
    }

    /**
     * Return the loader
     */
    public static ClassLoader getLoader()
    {
        return instance.loader;
    }

    /**
     * Return all loaded resources
     */
    public static Map<String, byte[]> getAllResources()
    {
        prepare();

        synchronized (instance.loader)
        {
            if (instance.loader instanceof JarClassLoader)
                ((JarClassLoader) instance.loader).getLoadedResources();
        }

        return new HashMap<String, byte[]>();
    }

    /**
     * Return all loaded classes
     */
    public static Map<String, Class<?>> getAllClasses()
    {
        prepare();

        synchronized (instance.loader)
        {
            if (instance.loader instanceof JarClassLoader)
                ((JarClassLoader) instance.loader).getLoadedClasses();
        }

        return new HashMap<String, Class<?>>();
    }

    /**
     * Return a resource as data stream from given resource name
     * 
     * @param name
     *        resource name
     */
    public static InputStream getResourceAsStream(String name)
    {
        prepare();

        synchronized (instance.loader)
        {
            return instance.loader.getResourceAsStream(name);
        }
    }

    /**
     * Return the list of loaded plugins.
     */
    public static ArrayList<PluginDescriptor> getPlugins()
    {
        return getPlugins(true);
    }

    /**
     * Return the list of loaded plugins.
     * 
     * @param wantBundled
     *        specify if we also want plugin implementing the {@link PluginBundled} interface.
     */
    public static ArrayList<PluginDescriptor> getPlugins(boolean wantBundled)
    {
        prepare();

        final ArrayList<PluginDescriptor> result = new ArrayList<PluginDescriptor>();

        // better to return a copy as we have async list loading
        synchronized (instance.plugins)
        {
            for (PluginDescriptor plugin : instance.plugins)
            {
                final Class<? extends Plugin> classPlug = plugin.getPluginClass();

                if (classPlug != null)
                {
                    if (wantBundled || !PluginBundled.class.isAssignableFrom(classPlug))
                        result.add(plugin);
                }
            }
        }

        return result;
    }

    /**
     * Return the list of loaded plugins which derive from the specified class.
     * 
     * @param clazz
     *        The class object defining the class we want plugin derive from.
     */
    public static ArrayList<PluginDescriptor> getPlugins(Class<?> clazz)
    {
        return getPlugins(clazz, false, false, true);
    }

    /**
     * Return the list of loaded plugins which derive from the specified class.
     * 
     * @param clazz
     *        The class object defining the class we want plugin derive from.
     * @param wantBundled
     *        specify if we also want plugin implementing the {@link PluginBundled} interface
     * @param wantAbstract
     *        specify if we also want abstract classes
     * @param wantInterface
     *        specify if we also want interfaces
     */
    public static ArrayList<PluginDescriptor> getPlugins(Class<?> clazz, boolean wantBundled, boolean wantAbstract,
            boolean wantInterface)
    {
        prepare();

        final ArrayList<PluginDescriptor> result = new ArrayList<PluginDescriptor>();

        if (clazz != null)
        {
            synchronized (instance.plugins)
            {
                for (PluginDescriptor pluginDescriptor : instance.plugins)
                {
                    final Class<? extends Plugin> classPlug = pluginDescriptor.getPluginClass();

                    if ((classPlug != null) && clazz.isAssignableFrom(classPlug))
                    {
                        // accept class ?
                        if ((wantAbstract || !ClassUtil.isAbstract(classPlug))
                                && (wantInterface || !classPlug.isInterface())
                                && (wantBundled || !PluginBundled.class.isAssignableFrom(classPlug)))
                            result.add(pluginDescriptor);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Return the list of "actionable" plugins (mean we can launch them from GUI).
     * 
     * @param wantBundled
     *        specify if we also want plugin implementing the {@link PluginBundled} interface
     */
    public static ArrayList<PluginDescriptor> getActionablePlugins(boolean wantBundled)
    {
        prepare();

        final ArrayList<PluginDescriptor> result = new ArrayList<PluginDescriptor>();

        synchronized (instance.plugins)
        {
            for (PluginDescriptor pluginDescriptor : instance.plugins)
            {
                final Class<? extends Plugin> classPlug = pluginDescriptor.getPluginClass();

                if (classPlug != null)
                {
                    if (pluginDescriptor.isActionable()
                            && (wantBundled || !PluginBundled.class.isAssignableFrom(classPlug)))
                        result.add(pluginDescriptor);
                }
            }
        }

        return result;
    }

    /**
     * Return the list of "actionable" plugins (mean we can launch them from GUI).<br>
     * By default plugin implementing the {@link PluginBundled} interface are not returned.
     */
    public static ArrayList<PluginDescriptor> getActionablePlugins()
    {
        return getActionablePlugins(false);
    }

    /**
     * @return the loading
     */
    public static boolean isLoading()
    {
        return instance.processor.hasWaitingTasks() || instance.loading;
    }

    /**
     * wait until loading completed
     */
    public static void waitWhileLoading()
    {
        while (isLoading())
            ThreadUtil.sleep(10);
    }

    public static boolean isLoaded(PluginDescriptor plugin, boolean acceptNewer)
    {
        return (getPlugin(plugin.getIdent(), acceptNewer) != null);
    }

    public static boolean isLoaded(String className)
    {
        return (getPlugin(className) != null);
    }

    public static PluginDescriptor getPlugin(PluginIdent ident, boolean acceptNewer)
    {
        prepare();

        synchronized (instance.plugins)
        {
            return PluginDescriptor.getPlugin(instance.plugins, ident, acceptNewer);
        }
    }

    public static PluginDescriptor getPlugin(String className)
    {
        prepare();

        synchronized (instance.plugins)
        {
            return PluginDescriptor.getPlugin(instance.plugins, className);
        }
    }

    public static Class<? extends Plugin> getPluginClass(String className)
    {
        prepare();

        final PluginDescriptor descriptor = getPlugin(className);

        if (descriptor != null)
            return descriptor.getPluginClass();

        return null;
    }

    /**
     * Loads the class with the specified binary name from the Plugin class loader.
     */
    public static Class<?> loadClass(String className) throws ClassNotFoundException
    {
        prepare();

        synchronized (instance.loader)
        {
            // try to load class and check we have a Plugin class at same time
            return instance.loader.loadClass(className);
        }
    }

    /**
     * Verify that specified plugins are valid.<br>
     * Return the error string if any (empty string = plugins are valid)
     */
    public static String verifyPluginsAreValid(ArrayList<PluginDescriptor> pluginsToVerify)
    {
        synchronized (instance.loader)
        {
            for (PluginDescriptor plugin : pluginsToVerify)
            {
                try
                {
                    // then try to load the plugin class as Plugin class
                    instance.loader.loadClass(plugin.getClassName()).asSubclass(Plugin.class);
                }
                catch (Error e)
                {
                    return "Fatal error while loading '" + plugin.getClassName() + "' class from "
                            + plugin.getJarFilename() + " :\n" + e.toString();
                }
                catch (ClassCastException e)
                {
                    return "Fatal error while loading '" + plugin.getClassName() + "' class from "
                            + plugin.getJarFilename() + " :\n" + IcyExceptionHandler.getErrorMessage(e, false)
                            + "Your plugin class should extends 'icy.plugin.abstract_.Plugin' class !";
                }
                catch (ClassNotFoundException e)
                {
                    return "Fatal error while loading '" + plugin.getClassName() + "' class from "
                            + plugin.getJarFilename() + " :\n" + IcyExceptionHandler.getErrorMessage(e, false)
                            + "Verify you correctly set the class name in your plugin description.";
                }
                catch (Exception e)
                {
                    return "Fatal error while loading '" + plugin.getClassName() + "' class from "
                            + plugin.getJarFilename() + " :\n" + IcyExceptionHandler.getErrorMessage(e, false);
                }
            }
        }

        return "";
    }

    /**
     * Load all classes from specified path
     */
    // private static ArrayList<String> loadAllClasses(String path)
    // {
    // // search for class names in that path
    // final HashSet<String> classNames = ClassUtil.findClassNamesInPath(path, true);
    // final ArrayList<String> result = new ArrayList<String>();
    //
    // synchronized (loader)
    // {
    // for (String className : classNames)
    // {
    // try
    // {
    // // try to load class
    // loader.loadClass(className);
    // }
    // catch (Error err)
    // {
    // // fatal error while loading class, store error String
    // result.add("Fatal error while loading " + className + " :\n" + err.toString() + "\n");
    // }
    // catch (ClassNotFoundException cnfe)
    // {
    // // ignore ClassNotFoundException (happen with private class)
    // }
    // catch (Exception exc)
    // {
    // result.add("Fatal error while loading " + className + " :\n" + exc.toString() + "\n");
    // }
    // }
    // }
    //
    // return result;
    // }

    public static boolean isJCLDisabled()
    {
        return instance.JCLDisabled;
    }

    public static void setJCLDisabled(boolean value)
    {
        instance.JCLDisabled = value;
    }

    /**
     * @return the logError
     */
    public static boolean getLogError()
    {
        return instance.logError;
    }

    public static void setLogError(boolean value)
    {
        instance.logError = value;
    }

    /**
     * Called when class loader
     */
    private void changed()
    {
        synchronized (updater)
        {
            initialized = true;

            // plugin list has changed
            updater.changed(new PluginLoaderEvent());
        }
    }

    @Override
    public void onChanged(EventHierarchicalChecker e)
    {
        final PluginLoaderEvent event = (PluginLoaderEvent) e;

        // start daemon plugins
        startDaemons();
        // notify listener we have changed
        fireEvent(event);
    }

    /**
     * Add a listener
     * 
     * @param listener
     */
    public static void addListener(PluginLoaderListener listener)
    {
        synchronized (instance.listeners)
        {
            instance.listeners.add(PluginLoaderListener.class, listener);
        }
    }

    /**
     * Remove a listener
     * 
     * @param listener
     */
    public static void removeListener(PluginLoaderListener listener)
    {
        synchronized (instance.listeners)
        {
            instance.listeners.remove(PluginLoaderListener.class, listener);
        }
    }

    /**
     * fire event
     */
    void fireEvent(PluginLoaderEvent e)
    {
        synchronized (listeners)
        {
            for (PluginLoaderListener listener : listeners.getListeners(PluginLoaderListener.class))
                listener.pluginLoaderChanged(e);
        }
    }

    public static void beginUpdate()
    {
        synchronized (instance.updater)
        {
            instance.updater.beginUpdate();
        }
    }

    public static void endUpdate()
    {
        synchronized (instance.updater)
        {
            instance.updater.endUpdate();

            if (!instance.updater.isUpdating())
            {
                // proceed pending tasks
                if (instance.needReload)
                    reloadAsynch();
            }
        }
    }

    public static boolean isUpdating()
    {
        return instance.updater.isUpdating();
    }

}
