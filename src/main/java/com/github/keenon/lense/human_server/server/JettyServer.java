package com.github.keenon.lense.human_server.server;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import java.io.File;

/**
 * Created by keenon on 10/7/15.
 *
 * Launches a Jetty server to get human annotations from people's browsers.
 */
public class JettyServer implements Runnable {
    // the keystore (with one key) we'll use to make the connection with the
    // broker
    private static final String KEYSTORE_LOCATION = "/etc/apache2/ssl/keystore";
    private static final String KEYSTORE_PASS = "passwd";
    private static final String WEB_APP_CONTEXT = "src/main/lense-webapp";

    public boolean useDevPorts = true;

    public static void main(String[] args) {
        new JettyServer().run();
    }

    @Override
    public void run() {
        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(500);

        Server server = new Server(threadPool);

        int publicPort = useDevPorts ? 8080 : 80;
        int securePort = useDevPorts ? 8443 : 443;

        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecureScheme("https");
        http_config.setSecurePort(securePort);
        http_config.setOutputBufferSize(32768);
        http_config.setRequestHeaderSize(8192);
        http_config.setResponseHeaderSize(8192);
        http_config.setSendServerVersion(true);
        http_config.setSendDateHeader(false);

        ServerConnector http = new ServerConnector(server,
                new HttpConnectionFactory(http_config));
        http.setPort(publicPort); // 8080
        http.setIdleTimeout(30000);
        server.addConnector(http);

        if (new File(KEYSTORE_LOCATION).exists()) {
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(KEYSTORE_LOCATION);
            sslContextFactory.setKeyStorePassword(KEYSTORE_PASS);
            sslContextFactory.setExcludeCipherSuites("SSL_RSA_WITH_DES_CBC_SHA",
                    "SSL_DHE_RSA_WITH_DES_CBC_SHA", "SSL_DHE_DSS_WITH_DES_CBC_SHA",
                    "SSL_RSA_EXPORT_WITH_RC4_40_MD5",
                    "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
                    "SSL_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA");

            // SSL HTTP Configuration
            HttpConfiguration https_config = new HttpConfiguration(http_config);
            https_config.addCustomizer(new SecureRequestCustomizer());

            // SSL Connector
            ServerConnector sslConnector = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                    new HttpConnectionFactory(https_config));
            sslConnector.setPort(securePort);
            server.addConnector(sslConnector);
        }

        WebAppContext context = new WebAppContext(WEB_APP_CONTEXT, "/");
        context.setServer(server);
        context.setInitParameter("cacheControl", "max-age=0,public");
        server.setHandler(context);

        context.addServlet(HumanWorkerWebSocketServlet.class, "/work-socket");

        org.eclipse.jetty.util.log.Log.setLog(new StdErrLog());
      /*
      // Disable Jetty logging
      org.eclipse.jetty.util.log.Log.setLog(new Logger {
        override def getName: String = "No logs"
        override def warn(msg: String, args: AnyRef*): Unit = log.warn("WARN: "+msg)
        override def warn(thrown: Throwable): Unit = thrown.printStackTrace()
        override def warn(msg: String, thrown: Throwable): Unit = {
          log.warn("WARN: "+msg)
          thrown.printStackTrace()
        }
        override def isDebugEnabled: Boolean = false
        override def getLogger(name: String): Logger = this
        override def ignore(ignored: Throwable): Unit = {}
        override def debug(msg: String, args: AnyRef*): Unit = {}
        override def debug(msg: String, value: Long): Unit = {}
        override def debug(thrown: Throwable): Unit = {}
        override def debug(msg: String, thrown: Throwable): Unit = {}
        override def setDebugEnabled(enabled: Boolean): Unit = {}
        override def info(msg: String, args: AnyRef*): Unit = {}
        override def info(thrown: Throwable): Unit = {}
        override def info(msg: String, thrown: Throwable): Unit = {}
      })
      */

        try {
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static class HumanWorkerWebSocketServlet extends WebSocketServlet {
        @Override
        public void configure(WebSocketServletFactory factory) {
            factory.register(HumanWorkerWebSocket.class);
        }
    }
}
