package smoketest;

import com.sun.org.apache.bcel.internal.generic.Type;
import jakarta.ws.rs.ext.ParamConverter;
import org.glassfish.jersey.internal.inject.ParamConverters;
import org.glassfish.jersey.internal.inject.ParamConverters.StringConstructor;
import smoketest.config.AutoScanFeature;
import smoketest.resource.MyResource;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MainApp {
  public static final byte[] debugMarker = "debugmarker".getBytes();

  private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

  // we start at port 8080
  public static final String BASE_URI = "http://localhost:";

  // Starts Grizzly HTTP server
  public static HttpServer startServer(String httpPort) {

    // scan packages
    final ResourceConfig config = new ResourceConfig();
    // config.packages(true, "com.mkyong");
    config.register(MyResource.class);

    // enable auto scan @Contract and @Service
    config.register(AutoScanFeature.class);

    LOGGER.info("Starting Server........");

    URI uri = URI.create(BASE_URI + httpPort + "/");
    System.out.println("Starting server at URI: " + uri);
    final HttpServer httpServer = GrizzlyHttpServerFactory.createHttpServer(uri, config);

    return httpServer;
  }

  public static void main(String[] args) {
    ParamConverter paramConverter =
        new StringConstructor()
            .getConverter(String.class, new GenericClass(String.class).getMyType(), null);
    Object pepe = paramConverter.fromString("Pepe");

    if (args.length == 0) {
      throw new RuntimeException("httpPort should be passed as first parameter");
    }
    try {

      final HttpServer httpServer = startServer(args[0]);

      // add jvm shutdown hook
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      System.out.println("Shutting down the application...");

                      httpServer.shutdownNow();

                      System.out.println("Done, exit.");
                    } catch (Exception e) {
                      Logger.getLogger(MainApp.class.getName()).log(Level.SEVERE, null, e);
                    }
                  }));

      System.out.println(String.format("Application started.%nStop the application using CTRL+C"));

      // block and wait shut down signal, like CTRL+C
      Thread.currentThread().join();

    } catch (InterruptedException ex) {
      Logger.getLogger(MainApp.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public static class GenericClass<T> {

    private final Class<T> type;

    public GenericClass(Class<T> type) {
      this.type = type;
    }

    public Class<T> getMyType() {
      return this.type;
    }
  }
}
