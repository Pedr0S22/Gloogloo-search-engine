package org.example;

import org.example.common.*;
import org.example.Queue.IQueue;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.rmi.UnmarshalException;

/**
 * The {@code Downloader} class is responsible for fetching URLs from a queue,
 * processing their contents, and distributing indexed data to remote barrels.
 * It also periodically synchronizes stopwords across the system.
 *
 * <p>This component is central to the distributed web indexing process.
 * It connects to RMI services: {@code IQueue}, {@code IGateway}, and {@code IBarrel}.
 *
 */
public class Downloader {
    private static final Logger logger = Logger.getLogger(Downloader.class.getName());
    private IQueue queue;
    private IGateway gateaway;
    private Map<String, IBarrel> barrels;

    // Variáveis para armazenar as URLs de configuração
    private String queueURL;
    private String gateawayURL;

    // Caminho do arquivo de propriedades
    private static final String GATEWAY_CONFIG_FILE = "frontend/src/main/resources/gateway.properties";
    private static final String QUEUE_CONFIG_FILE = "backend/src/main/java/org/example/Properties/queue.properties";

    private Set<String> stopWords;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static volatile boolean running = true;

    /**
     * Constructs a new {@code Downloader} instance.
     * Initializes the local stopword set and establishes RMI connections
     * to the queue and gateway services.
     */
    public Downloader() {
        this.stopWords = new HashSet<>();
        conectar();
    }

    /**
     * Loads configuration properties for the queue and gateway RMI services.
     */
    private synchronized void loadProperties() {
        try {
            // Carregar as propriedades de queue-config.properties
            Properties queueProps = new Properties();
            try (FileInputStream input = new FileInputStream(QUEUE_CONFIG_FILE)) {
                queueProps.load(input);
            }
            // Obter as URLs dos serviços RMI da Queue
            String host = queueProps.getProperty("queue.rmi.host");
            String port = queueProps.getProperty("queue.rmi.port");
            String serviceName = queueProps.getProperty("queue.rmi.service_name");
            this.queueURL = "rmi://" + host + ":" + port + "/" + serviceName;

            // Carregar as propriedades de gateway-config.properties
            Properties gateProps = new Properties();
            try (FileInputStream input = new FileInputStream(GATEWAY_CONFIG_FILE)) {
                gateProps.load(input);
            }
            host = gateProps.getProperty("rmi.host");
            port = gateProps.getProperty("rmi.port");
            serviceName = gateProps.getProperty("rmi.service_name");
            this.gateawayURL = "rmi://" + host + ":" + port + "/" + serviceName;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao carregar as propriedades: " + e.getMessage());
        }
    }

    /**
     * Main processing logic: retrieves a URL from the queue and processes it if it is not already indexed.
     */
    private void run() {
        try {
            String url = queue.getURL();
            if (url != null) {
                processContent(url);
            } else {
                System.out.println("Fila vazia. Aguardando novas URLs...");
            }
            Thread.sleep(2000);
        } catch (RemoteException e) {
            logger.log(Level.SEVERE, "Erro ao obter URL da fila: " + e.getMessage());
        } catch (InterruptedException e) {
            System.out.println("Downloader foi interrompido.");
        }
    }

    /**
     * Processes the content of the given URL by downloading, parsing, and indexing its contents.
     * <p>
     * This method performs the following steps:
     * <ul>
     *     <li>Retrieves the list of registered barrels from the gateway.</li>
     *     <li>Selects a random barrel and checks if the given URL is already indexed.</li>
     *     <li>If not indexed, fetches the HTML content using Jsoup.</li>
     *     <li>Extracts the title, body text, and main citation from the content.</li>
     *     <li>Normalizes and counts word frequencies, filtering out stopwords.</li>
     *     <li>Extracts all valid hyperlinks from the page.</li>
     *     <li>Distributes the indexing task across all available barrels in separate threads.</li>
     *     <li>If at least one barrel successfully indexes the URL, filters and adds discovered links to the queue.</li>
     *     <li>If none succeeded, re-adds the original URL to the queue for future processing.</li>
     * </ul>
     *
     * @param url the URL to process
     * @throws RemoteException if a remote communication error occurs during barrel interaction
     */
    private synchronized void processContent(String url) throws RemoteException {
        this.barrels = this.gateaway.getBarrels();
        Random random = new Random();
        List<String> keys = new ArrayList<>(this.barrels.keySet());
        if (keys.isEmpty()) {
            System.out.println("Nenhum barrel disponível.");
            return;
        }
        String randomKey = keys.get(random.nextInt(keys.size()));
        IBarrel barrelc = this.barrels.get(randomKey);

        if (!barrelc.containsUrl(url)) {
            try {
                System.out.println("Processando URL: " + url);
                Document doc = Jsoup.connect(url).get();
                String title = doc.title();
                String bodyText = doc.body().text();
                Map<String, Integer> palavras = normalizeWords(bodyText);
                String citacao = doc.select("p").first() != null ? doc.select("p").first().text() : title;
                List<String> listaLinks = doc.select("a[href]").eachAttr("abs:href").stream()
                        .filter(link -> link.startsWith("http") && !link.contains("#") && !link.contains("login"))
                        .distinct().toList();

                List<Thread> threads = new ArrayList<>();
                List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

                for (Map.Entry<String, IBarrel> entry : this.barrels.entrySet()) {
                    String chave = entry.getKey();
                    IBarrel barrel = entry.getValue();

                    Thread thread = new Thread(() -> {
                        try {
                            barrel.addToIndex(palavras, url, listaLinks, title, citacao);
                            synchronized (results) {
                                results.add(true);
                            }
                        } catch (UnmarshalException e) {
                            logger.log(Level.WARNING, "RMI connection problem with barrel " + chave, e);
                            synchronized (results) {
                                results.add(false);
                            }
                            try {
                                this.gateaway.unregisterBarrel(chave);
                                System.out.println("Foi removido a URL: " + chave);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        } catch (Exception e) {
                            synchronized (results) {
                                results.add(false);
                            }
                            // Só faz unregister se NÃO for erro de "database is locked"
                            if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("database is locked")) {
                                try {
                                    this.gateaway.unregisterBarrel(chave);
                                    System.out.println("Foi removido a URL: " + chave);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            } else {
                                System.out.println("Erro de lock no barrel, a ligação será mantida: " + chave);
                            }
                        }
                    });
                    threads.add(thread);
                    thread.start();
                }

                for (Thread thread : threads) {
                    thread.join();
                }

                if (!results.isEmpty() && results.stream().anyMatch(Boolean::booleanValue)) {
                    for (String link : listaLinks) {
                        String keyAleatoria = keys.get(random.nextInt(keys.size()));
                        IBarrel barrelAleatorio = this.barrels.get(keyAleatoria);
                        if (!barrelAleatorio.containsUrl(link)) {
                            this.queue.addURL(link);
                        }
                    }
                } else {
                    this.queue.addURL(url);
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro ao processar a URL: " + e.getMessage());
            }

        }
    }

    /**
     * Normalizes and tokenizes text, removing punctuation and diacritics, and counts word frequencies.
     *
     * @param text the input text
     * @return a map of word frequencies excluding stopwords
     */
    private Map<String, Integer> normalizeWords(String text) {
        text = Normalizer.normalize(text, Normalizer.Form.NFD);
        text = text.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        text = text.replaceAll("[\\p{Punct}]", "").toLowerCase();
        String[] palavras = text.split("\\s+");
        Map<String, Integer> frequenciaPalavras = new HashMap<>();

        for (String palavra : palavras) {
            if (!palavra.isBlank() && !stopWords.contains(palavra)) {
                frequenciaPalavras.put(palavra, frequenciaPalavras.getOrDefault(palavra, 0) + 1);
            }
        }
        return frequenciaPalavras;
    }

    /**
     * Periodically fetches updated stopwords from a random barrel and synchronizes them with the queue.
     */
    private void stopWordsUpdater() {
        // Setting the time for each stop word catch
        int time = 1;

        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Get barrels from Gateway and pick one randomly
                this.barrels = this.gateaway.getBarrels();
                if (this.barrels.isEmpty()) {
                    System.out.println("Nenhum barrel disponível. Aguardando próxima tentativa...");
                    return;
                }
    
                Random rand = new Random();
                List<IBarrel> barrelList = new ArrayList<>(this.barrels.values());
                IBarrel selectedBarrel = barrelList.get(rand.nextInt(barrelList.size()));
    
                // Get stopwords from selected barrel
                List<String> barrelStopwords = selectedBarrel.getFrequentWords();
    
                if (barrelStopwords == null || barrelStopwords.isEmpty()) {
                    System.out.println("Barrel não retornou stopwords. Aguardando próxima tentativa...");
                    return;
                }
    
                // Send stopwords to Queue to update file and in-memory list
                queue.addStopWords(barrelStopwords);
                System.out.println("Stopwords enviadas para a queue.");
    
                // Get updated stopwords from Queue and store locally
                List<String> updatedStopwords = queue.getStopwords();
                stopWords = new HashSet<>(updatedStopwords);
                System.out.println("Stopwords sincronizadas. Total: " + stopWords.size());
    
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Erro durante sincronização de stopwords: " + e.getMessage(), e);
            }
        }, time, time, TimeUnit.MINUTES);
    }
    
    /**
     * Connects to the RMI services for queue and gateway.
     */
    public void conectar() {
        try {
            loadProperties();
            this.queue = (IQueue) Naming.lookup(queueURL);
            this.gateaway = (IGateway) Naming.lookup(gateawayURL);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Erro ao conectar: " + e.getMessage());
        }
    }

    /**
     * Shuts down the scheduled stopword synchronization task.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /**
     * The entry point of the Downloader application. Starts multiple downloader threads and handles graceful shutdown.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        int numDownloaders = 1;

        Downloader stopwordManager = new Downloader();
        stopwordManager.stopWordsUpdater(); 

        for (int i = 0; i < numDownloaders; i++) {
            Thread t = new Thread(() -> {
                Downloader d = new Downloader();
                while (running) {
                    d.run();
                }
            });
            t.start();
        }

        // Exiting cleanly and stop the scheduler
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            running = false;
            stopwordManager.shutdown();
        }));

    }
}
