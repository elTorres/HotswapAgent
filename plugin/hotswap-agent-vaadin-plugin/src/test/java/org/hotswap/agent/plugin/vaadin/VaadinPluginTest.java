package org.hotswap.agent.plugin.vaadin;

import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;

import com.vaadin.flow.server.VaadinServlet;
import org.junit.Test;

public class VaadinPluginTest {

    @Test
    public void registersServletWhenOnlyApplicationLoaderCanLoadIntegration() throws Exception {
        String pluginName = VaadinPlugin.class.getName();
        String integrationName = "org.hotswap.agent.plugin.vaadin.VaadinIntegration";
        URL[] pluginLocations = {VaadinPlugin.class.getProtectionDomain().getCodeSource().getLocation()};

        try (URLClassLoader agentLoader = new URLClassLoader(pluginLocations, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(integrationName)) {
                    throw new ClassNotFoundException(name);
                }
                if (name.equals(pluginName) || name.startsWith(pluginName + "$")) {
                    Class<?> type = findLoadedClass(name);
                    return type == null ? findClass(name) : type;
                }
                return super.loadClass(name, resolve);
            }
        }; URLClassLoader applicationLoader = new URLClassLoader(pluginLocations, agentLoader)) {
            Class<?> pluginClass = agentLoader.loadClass(pluginName);
            Object plugin = pluginClass.getConstructor().newInstance();
            Field loader = pluginClass.getDeclaredField("appClassLoader");
            loader.setAccessible(true);
            loader.set(plugin, applicationLoader);

            pluginClass.getMethod("registerServlet", Object.class).invoke(plugin, new VaadinServlet());

            Field reloadCommand = pluginClass.getDeclaredField("reloadCommand");
            reloadCommand.setAccessible(true);
            assertNotNull("Servlet registration should enable live reload", reloadCommand.get(plugin));
        }
    }
}
