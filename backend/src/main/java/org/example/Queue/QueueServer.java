package org.example.Queue;

import java.rmi.Naming;
import java.rmi.registry.LocateRegistry;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Starts the RMI server for the Queue service.
 * 
 * Loads configuration from a properties file and registers the Queue implementation
 * with the RMI registry using the specified host, port, and service name.
 * 
 * This class is intended to be run as a standalone Java application.
 * 
 */
public class QueueServer {
    private static final String CONFIG_FILE = "backend/src/main/java/org/example/Properties/queue.properties";

    /**
     * Entry point for starting the Queue RMI server.
     * 
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            // Carregar configurações do ficheiro properties
            Properties prop = new Properties();
            try (FileInputStream input = new FileInputStream(CONFIG_FILE)) {
                prop.load(input);
            }
            String prefix = "queue";

            String host = prop.getProperty(prefix + ".rmi.host", "localhost");
            int port = Integer.parseInt(prop.getProperty(prefix + ".rmi.port", "1112"));
            String serviceName = prop.getProperty(prefix + ".rmi.service_name", "QueueService");

            // Criar e exportar o registo RMI
            LocateRegistry.createRegistry(port);

            // Criar instância da implementação da fila
            IQueue queue = new QueueImp();

            // Registar o objeto no RMI Registry
            String url = "rmi://" + host + ":" + port + "/" + serviceName;
            Naming.rebind(url, queue);

            System.out.println("Servidor RMI da Queue está pronto em " + url);
        } catch (IOException | NumberFormatException e) {
            System.err.println("Erro ao carregar configurações: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Erro no servidor RMI: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
