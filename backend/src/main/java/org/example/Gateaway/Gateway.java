package org.example.Gateaway;

import java.io.*;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.text.Normalizer;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import org.example.Queue.*;
import org.example.Statistics.BarrelStats;
import org.example.common.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * The Gateway class is a server-side RMI implementation that handles
 * interactions between various barrels
 * and a queue service. It provides methods for inserting URLs into the queue,
 * performing searches, retrieving
 * statistics, and managing barrel registrations. The Gateway also integrates
 * with external services such as
 * the Hacker News API and provides an interface for clients to request and
 * receive updates.
 */
public class Gateway extends UnicastRemoteObject implements IGateway {

    private IBarrel selectedBarrel;
    private String selectedBarrelId;
    private IQueue queue;
    private Random random = new Random();

    private static final String GATEWAY_CONFIG_FILE = "frontend/src/main/resources/gateway.properties";
    private static final String QUEUE_CONFIG_FILE = "backend/src/main/java/org/example/Properties/queue.properties";
    private static final String BARREL_CONFIG_FILE = "backend/src/main/java/org/example/Properties/barrel.properties";
    private static final long serialVersionUID = 1L;

    private List<IStatistics> listeners = new ArrayList<>();
    private SystemStatistics currentStats;

    private Map<String, IBarrel> activeBarrels = new HashMap<>();
    private Map<String, BarrelStats> responseTimes = new HashMap<>();

    private static final String TOP_STORIES_URL = "https://hacker-news.firebaseio.com/v0/topstories.json";
    private static final String ITEM_URL = "https://hacker-news.firebaseio.com/v0/item/";

    private static String API_KEY;

    /**
     * Initializes the Gateway object and sets up the system statistics.
     *
     * @throws RemoteException If an error occurs while initializing the object.
     */
    public Gateway() throws RemoteException {
        super();
        this.currentStats = new SystemStatistics(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }

    /**
     * The main method that initializes and runs the Gateway service, connecting to
     * the Queue and Barrel services.
     * It also binds the Gateway object to the RMI registry for remote clients to
     * access.
     *
     * @param args Command-line arguments (not used).
     */
    public static void main(String[] args) {
        try {
            Properties prop = new Properties();
            try (FileInputStream fis = new FileInputStream(GATEWAY_CONFIG_FILE)) {
                prop.load(fis);
            }

            String host = prop.getProperty("rmi.host", "localhost");
            int port = Integer.parseInt(prop.getProperty("rmi.port", "1099"));
            String serviceName = prop.getProperty("rmi.service_name", "GatewayService");

            API_KEY = prop.getProperty("api.key");

            Gateway gateway = new Gateway();

            LocateRegistry.createRegistry(port);
            Naming.rebind("rmi://" + host + ":" + port + "/" + serviceName, gateway);
            System.out.println("Gateway is running on rmi://" + host + ":" + port + "/" + serviceName);

            // Initialize services
            gateway.initialize();

        } catch (Exception e) {
            System.err.println("Failed to start Gateway: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initializes the Gateway by loading configuration files and connecting to the
     * Queue and Barrel services.
     * It also checks for active barrels.
     */
    private void initialize() {
        try {
            Properties queueProp = loadProperties(QUEUE_CONFIG_FILE);
            Properties barrelProp = loadProperties(BARREL_CONFIG_FILE);

            // Connect to Queue
            String queueUrl = getRmiUrl(queueProp, "queue");
            System.out.println("Queue URL: " + queueUrl);
            queue = (IQueue) Naming.lookup(queueUrl);
            System.out.println("Connected to Queue: " + queueUrl);

            // Check available barrels
            checkActiveBarrels(barrelProp);

        } catch (IOException e) {
            System.err.println("Error loading properties: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred during initialization: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Loads properties from the specified file path.
     *
     * @param filePath The path of the properties file to load.
     * @return The loaded properties.
     * @throws IOException If an error occurs while loading the file.
     */
    private Properties loadProperties(String filePath) throws IOException {
        Properties prop = new Properties();
        try (FileInputStream input = new FileInputStream(filePath)) {
            prop.load(input);
        }
        if (prop.isEmpty()) {
            throw new IOException("Properties file is empty or not loaded: " + filePath);
        }
        return prop;
    }

    /**
     * Constructs the RMI URL for a service based on the provided properties.
     *
     * @param prop   The properties containing RMI connection information.
     * @param prefix The prefix used to construct the property keys.
     * @return The constructed RMI URL.
     */
    private String getRmiUrl(Properties prop, String prefix) {
        String host = prop.getProperty(prefix + ".rmi.host", "localhost");
        String port = prop.getProperty(prefix + ".rmi.port", "1112");
        String service = prop.getProperty(prefix + ".rmi.service_name", "QueueService");

        if (host == null || port == null || service == null) {
            throw new IllegalArgumentException("Missing RMI configuration for " + prefix);
        }
        return "rmi://" + host + ":" + port + "/" + service;
    }

    /**
     * Checks the active barrels defined in the properties file and attempts to
     * establish connections with them.
     * Active barrels are stored in the activeBarrels map.
     *
     * @param prop The properties file containing barrel connection information.
     * @throws IOException If an error occurs while connecting to the barrels.
     */
    private void checkActiveBarrels(Properties prop) throws IOException {
        activeBarrels.clear();
        int i = 1;

        while (true) {
            String barrelPrefix = "barrel" + i;
            String host = prop.getProperty(barrelPrefix + ".rmi.host");
            String port = prop.getProperty(barrelPrefix + ".rmi.port");
            String serviceName = prop.getProperty(barrelPrefix + ".rmi.service_name");

            // No more barrels defined in properties
            if (host == null || port == null || serviceName == null) {
                break;
            }

            String barrelUrl = String.format("rmi://%s:%s/%s", host, port, serviceName);

            try {
                IBarrel barrel = (IBarrel) Naming.lookup(barrelUrl);
                activeBarrels.put(barrelPrefix, barrel);

                // Initialize stats if not already
                responseTimes.putIfAbsent(barrelPrefix, new BarrelStats());

                System.out.println("Connected to active barrel: " + barrelUrl);
            } catch (Exception e) {
                System.err.println("Could not connect to barrel at: " + barrelUrl);
                System.err.println("Reason: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }

            i++;
        }

        if (activeBarrels.isEmpty()) {
            System.err.println("No active barrels available!");
        } else {
            selectRandomBarrel(); // pick one from available ones
        }
    }

    /**
     * Selects a random barrel from the list of active barrels.
     */
    private void selectRandomBarrel() {
        List<String> barrelIds = new ArrayList<>(activeBarrels.keySet());
        if (!barrelIds.isEmpty()) {
            selectedBarrelId = barrelIds.get(random.nextInt(barrelIds.size()));
            selectedBarrel = activeBarrels.get(selectedBarrelId);
            System.out.println("Connected to selected barrel: " + selectedBarrelId);
        }
    }

    /**
     * Inserts a URL into the queue for further processing.
     *
     * @param url The URL to insert into the queue.
     * @throws RemoteException If the Queue service is not initialized.
     */
    public void insertURL(String url) throws RemoteException {
        if (queue == null) {
            throw new RemoteException("Queue service is not initialized.");
        }
        queue.addURL(url);
        System.out.println("URL inserted successfully into Queue");
    }

    /**
     * Inserts a URL at the beginning of the queue for further processing.
     *
     * @param url The URL to insert into the queue.
     * @throws RemoteException If the Queue service is not initialized.
     */
    public void addFirst(String url) throws RemoteException {
        if (queue == null) {
            throw new RemoteException("Queue service is not initialized.");
        }
        queue.addFirst(url);
        System.out.println("URL inserted successfully into Queue");
    }

    /**
     * Performs a search across the active barrels for the given search terms.
     * <p>
     * A barrel is selected randomly via {@code refreshAndSelectBarrel()} and tried
     * first. If it fails or returns
     * no results, the remaining barrels are tried in order. All barrels are
     * instructed to update their top-word
     * statistics before searching. The method records response times and updates
     * search-related statistics.
     * </p>
     *
     * @param search An {@link ArrayList} of keywords to search for.
     * @return A list of {@link SearchResult} objects containing results from the
     *         first successful barrel,
     *         or an empty list if all barrels fail or return no results.
     * @throws RemoteException If a remote communication error occurs.
     */
    public List<SearchResult> search(ArrayList<String> search) throws RemoteException {
        // Refresh barrels and select a random barrel every time a search is performed
        refreshAndSelectBarrel();

        if (selectedBarrel == null) {
            System.out.println("[Gateway] No barrel available after refreshing.");
            return new ArrayList<>();
        }

        List<SearchResult> results = new ArrayList<>();
        boolean searchSucceeded = false;

        // update top words in database
        for (Map.Entry<String, IBarrel> barrelEntry : activeBarrels.entrySet()) {
            IBarrel barrel = barrelEntry.getValue();

            barrel.updateTopWords(search);
        }

        List<Map.Entry<String, IBarrel>> barrelsToTry = new ArrayList<>(activeBarrels.entrySet());

        // Ensure selected barrel is first
        barrelsToTry.sort((entry1, entry2) -> {
            boolean isFirstSelected = entry1.getKey().equals(selectedBarrelId);
            boolean isSecondSelected = entry2.getKey().equals(selectedBarrelId);

            if (isFirstSelected && !isSecondSelected)
                return -1;
            if (!isFirstSelected && isSecondSelected)
                return 1;
            return 0;
        });

        // Try searching with all active barrels
        for (Map.Entry<String, IBarrel> barrelEntry : barrelsToTry) {
            String barrelId = barrelEntry.getKey();
            IBarrel barrel = barrelEntry.getValue();

            System.out.println("[Gateway] Trying search on barrel: " + barrelId);

            try {
                long startTime = System.nanoTime();
                results = barrel.search(search);
                long endTime = System.nanoTime();
                double responseTime = (endTime - startTime) / 1_000_000.0;
                responseTime = (double) Math.round(responseTime * 100) / 100;

                // Update BarrelStats for the current barrel
                BarrelStats stats = responseTimes.get(barrelId);
                stats.addResponseTime(responseTime);

                updateStatistics(search, responseTime);

                selectedBarrel = barrel;
                selectedBarrelId = barrelId;
                searchSucceeded = true;
                System.out.println("[Gateway] Finished search on barrel: " + barrelId);
                break;

            } catch (IOException e) {
                System.err.println("[Gateway] Error during search on barrel " + barrelId + ": " + e.getMessage());
            }
        }

        if (!searchSucceeded) {
            System.err.println("[Gateway] All barrels failed for search: " + search);
        }

        return results;
    }

    /**
     * Refreshes the active barrels list and selects a random barrel for the search.
     */
    private void refreshAndSelectBarrel() {
        System.out.println("[Gateway] Refreshing active barrels...");
        try {
            checkActiveBarrels(loadProperties(BARREL_CONFIG_FILE));
            if (selectedBarrel == null && !activeBarrels.isEmpty())
                selectRandomBarrel();
        } catch (IOException e) {
            System.err.println("[Gateway] Error refreshing active barrels: " + e.getMessage());
        }
    }

    /**
     * Retrieves the top search terms from the currently selected barrel.
     *
     * @return A list of top search terms.
     * @throws RemoteException If an error occurs while retrieving the top searches.
     */
    private List<String> getTopSearches() {
        if (selectedBarrel == null || selectedBarrelId == null)
            selectRandomBarrel();

        try {
            List<String> topSearches = selectedBarrel.getTopSearches();

            return topSearches != null ? topSearches : new ArrayList<>();

        } catch (IOException e) {
            System.err.println("[Gateway] Error during getTopSearches on selected barrel " + selectedBarrelId + ": "
                    + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Updates the system statistics with the latest search results and response
     * times.
     * This includes updating the top search terms, response times, and barrel index
     * sizes.
     * It notify all clients registered.
     *
     * @param search       The list of search terms that were queried.
     * @param responseTime The response time of the search operation.
     */
    private void updateStatistics(ArrayList<String> search, double responseTime) {
        List<String> topSearches = new ArrayList<>();
        HashMap<String, Double> response = new HashMap<>();
        HashMap<String, Integer> barrelSizes = new HashMap<>();

        response.put(selectedBarrelId, responseTime);
        topSearches = getTopSearches();

        for (Map.Entry<String, IBarrel> barrelEntry : activeBarrels.entrySet()) {
            String barrelId = barrelEntry.getKey();
            IBarrel barrel = barrelEntry.getValue();

            try {
                // Query the real barrel size dynamically from the barrel itself
                int barrelSize = barrel.getSize(); // Assuming IBarrel has a method getBarrelSize
                barrelSizes.put(barrelId, barrelSize);
            } catch (RemoteException e) {
                System.err.println("[Gateway] Failed to get size for barrel: " + barrelId);
                barrelSizes.put(barrelId, 0); // Default to 0 if error occurs
            }
        }

        // Add average response times to the statistics
        HashMap<String, Double> averageResponseTimes = new HashMap<>();
        for (Map.Entry<String, BarrelStats> entry : responseTimes.entrySet()) {
            String barrelId = entry.getKey();
            BarrelStats stats = entry.getValue();
            averageResponseTimes.put(barrelId, stats.getAverageResponseTime());
        }

        // Set the updated data to currentStats
        currentStats.setTopSearches(topSearches);
        currentStats.setResponseTimes(averageResponseTimes);
        currentStats.setBarrelIndexSizes(barrelSizes); // Add barrel sizes

        // Notify listeners after updating
        try {
            notifyListeners(); // Notify clients of the updated stats
        } catch (RemoteException e) {
            System.err.println("Failed to notify listeners: " + e.getMessage());
        }
    }

    /**
     * Retrieves the current system statistics, including barrel sizes and average
     * response times.
     *
     * @return The current system statistics.
     * @throws RemoteException If an error occurs while retrieving the statistics.
     */
    public synchronized SystemStatistics getStatistics() throws RemoteException {
        HashMap<String, Integer> barrelSizes = new HashMap<>();
        for (Map.Entry<String, IBarrel> barrelEntry : activeBarrels.entrySet()) {
            String barrelId = barrelEntry.getKey();
            IBarrel barrel = barrelEntry.getValue();

            try {
                // Query the real barrel size dynamically from the barrel itself
                int barrelSize = barrel.getSize(); // Assuming IBarrel has a method getBarrelSize
                barrelSizes.put(barrelId, barrelSize);
            } catch (RemoteException e) {
                System.err.println("[Gateway] Failed to get size for barrel: " + barrelId);
                barrelSizes.put(barrelId, 0); // Default to 0 if error occurs
            }
        }
        this.currentStats.setBarrelIndexSizes(barrelSizes);
        return currentStats;
    }

    /**
     * Attempts to retrieve URL connections from a given URL.
     * <p>
     * A barrel is selected randomly via {@code refreshAndSelectBarrel()} and tried
     * first. If it fails or returns
     * no results, the method iterates through the remaining barrels in order. It
     * records response times and
     * updates statistics accordingly.
     * </p>
     *
     * @param url The URL for which to retrieve connection information.
     * @return A {@link SearchResult} containing the found connection data, or an
     *         error message if all barrels fail.
     * @throws RemoteException If a remote communication error occurs.
     */
    public SearchResult getConnections(String url) throws RemoteException {
        // Refresh barrels and select a random barrel every time a getConnections is
        // performed
        refreshAndSelectBarrel();

        if (selectedBarrel == null) {
            System.out.println("[Gateway] No barrel available after refreshing.");
            return new SearchResult("No barrel available", Collections.emptyList());
        }

        SearchResult result = new SearchResult();
        boolean searchSucceeded = false;

        List<Map.Entry<String, IBarrel>> barrelsToTry = new ArrayList<>(activeBarrels.entrySet());

        // Ensure selected barrel is first
        barrelsToTry.sort((entry1, entry2) -> {
            boolean isFirstSelected = entry1.getKey().equals(selectedBarrelId);
            boolean isSecondSelected = entry2.getKey().equals(selectedBarrelId);

            if (isFirstSelected && !isSecondSelected)
                return -1;
            if (!isFirstSelected && isSecondSelected)
                return 1;
            return 0;
        });

        // Try searching with all active barrels if something went wrong
        for (Map.Entry<String, IBarrel> barrelEntry : barrelsToTry) {

            String barrelId = barrelEntry.getKey();
            IBarrel barrel = barrelEntry.getValue();

            System.out.println("[Gateway] Trying search on barrel: " + barrelId);

            try {
                long startTime = System.nanoTime();
                result = barrel.getConnections(url);
                long endTime = System.nanoTime();
                double responseTime = (endTime - startTime) / 1_000_000.0;
                responseTime = (double) Math.round(responseTime * 100) / 100;

                BarrelStats stats = responseTimes.get(barrelId);
                stats.addResponseTime(responseTime);

                ArrayList<String> url_ = new ArrayList<>();
                url_.add(url);

                updateStatistics(url_, responseTime);

                selectedBarrel = barrel;
                selectedBarrelId = barrelId;
                searchSucceeded = true;
                System.out.println("[Gateway] Successful check connections on barrel: " + barrelId);
                break;

            } catch (RemoteException e) {
                System.err.println("[Gateway] Error during getConnections: " + e.getMessage());
            }
        }

        if (!searchSucceeded) {
            System.err.println("[Gateway] All barrels failed for search: " + url);
            return new SearchResult("Error occurred for: " + url, Collections.emptyList());
        }

        return result;
    }

    /**
     * Registers a listener for system statistics updates. The listener will be
     * notified of any changes to the statistics.
     *
     * @param listener The listener to register.
     * @throws RemoteException If an error occurs while registering the listener.
     */
    public synchronized void registerStatisticsListener(IStatistics listener) throws RemoteException {
        listeners.add(listener);
        System.out.println("Client registered for statistics updates.");
    }

    /**
     * Notifies all registered listeners with the current system statistics.
     *
     * @throws RemoteException If an error occurs while notifying the listeners.
     */
    private void notifyListeners() throws RemoteException {
        Iterator<IStatistics> iterator = listeners.iterator();
        while (iterator.hasNext()) {
            IStatistics listener = iterator.next();
            try {
                listener.updateStatistics(currentStats);
            } catch (RemoteException e) {
                System.err.println("Failed to notify a listener: " + e.getMessage());
                iterator.remove();
            }
        }
    }

    /**
     * Broadcasts updated statistics to all listeners, including clients who have
     * registered for updates.
     *
     * @param stats The updated statistics to broadcast.
     * @throws RemoteException If an error occurs while broadcasting the statistics.
     */
    public synchronized void broadcastStatistics(SystemStatistics stats) throws RemoteException {
        for (IStatistics listener : listeners) {
            try {
                listener.updateStatistics(stats);
            } catch (RemoteException e) {
                System.err.println("Failed to notify a listener: " + e.getMessage());
            }
        }
    }

    /**
     * Registers a new barrel with the Gateway, synchronizing it with any existing
     * barrels if necessary.
     *
     * @param rmi The RMI URL of the barrel to register.
     * @throws RemoteException If an error occurs while registering the barrel.
     */
    public void registarBarrel(String rmi) throws RemoteException {
        try {
            try {
                unregisterBarrel(rmi);
            } catch (RemoteException e) {
                System.err.println("Failed to unregister barrel: " + e.getMessage());
            }

            IBarrel barrel = (IBarrel) Naming.lookup(rmi);
            IBarrel sinc = null;

            System.out.println(activeBarrels.size());

            if (!activeBarrels.isEmpty()) {

                for (String chave : activeBarrels.keySet()) {
                    IBarrel sincc = activeBarrels.get(chave);
                    sinc = sincc;
                    break; // Remove este break se quiser processar todos os barrels
                }
                sinc.sync(barrel);

            }
            barrel.connect();

            this.activeBarrels.put(rmi, barrel);
            System.out.println("Barrel registado com sucesso: " + rmi);

        } catch (NotBoundException e) {
            System.err.println("Erro ao registar o Barrel: " + e.getMessage());
            throw new RuntimeException(e);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Unregisters a barrel from the Gateway.
     *
     * @param rmi The RMI URL of the barrel to unregister.
     * @throws RemoteException If an error occurs while unregistering the barrel.
     */
    public void unregisterBarrel(String rmi) throws RemoteException {
        if (this.activeBarrels.remove(rmi) != null) {
            System.out.println("Barrel removido com sucesso: " + rmi);
        } else {
            System.err.println("Erro: Barrel não encontrado para remoção: " + rmi);
        }
    }

    /**
     * Retrieves the list of active barrels currently connected to the Gateway.
     *
     * @return A map of barrel IDs to barrel objects.
     * @throws RemoteException If an error occurs while retrieving the barrels.
     */
    public Map<String, IBarrel> getBarrels() throws RemoteException {
        return new HashMap<>(this.activeBarrels);
    }

    /**
     * Retrieves a list of stopwords from the Queue service.
     *
     * @return A list of stopwords.
     * @throws RemoteException If an error occurs while retrieving the stopwords.
     */
    public List<String> getStopwords() throws RemoteException {
        if (queue != null) {
            return queue.getStopwords();
        }
        throw new RemoteException("QueueServer not connected.");
    }

    /**
     * Performs a search on Hacker News top stories and adds URLs to the queue if
     * any
     * of the search terms are found in the article's title, text, or HTML content.
     * <p>
     * This method queries the Hacker News API for the top stories, normalizes the
     * search
     * title by removing diacritical marks and punctuation, and then searches for
     * each
     * term in the title and text of the articles. If no match is found, it fetches
     * the
     * HTML content of the article and checks there as well.
     * </p>
     *
     * @param title the search title used to match against Hacker News articles'
     *              titles, texts, and HTML content.
     * @throws RemoteException if there is a remote communication error, including
     *                         failures to connect to the Hacker News API.
     */
    @Override
    public void hacker(String title) throws RemoteException {
        try {
            String topStoriesJson = Jsoup.connect(TOP_STORIES_URL)
                    .ignoreContentType(true)
                    .execute()
                    .body();

            JSONArray topStoryIds = new JSONArray(topStoriesJson);
            int count = 0;

            // Normalizar e dividir o título de pesquisa
            title = Normalizer.normalize(title, Normalizer.Form.NFD)
                    .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                    .replaceAll("[\\p{Punct}]", "")
                    .toLowerCase();
            String[] searchWords = title.split("\\s+");

            for (int i = 0; i < topStoryIds.length() && count < 30; i++) {
                int id = topStoryIds.getInt(i);
                String storyJson = Jsoup.connect(ITEM_URL + id + ".json")
                        .ignoreContentType(true)
                        .execute()
                        .body();

                JSONObject story = new JSONObject(storyJson);

                if (story.has("title")) {
                    String storyTitle = story.getString("title").toLowerCase();
                    String storyText = story.has("text") ? story.getString("text").toLowerCase() : "";
                    String url = story.has("url") ? story.getString("url") : "";

                    boolean containsAnyWord = false;

                    // Verifica no título ou texto da notícia (API)
                    for (String word : searchWords) {
                        if (storyTitle.contains(word) || storyText.contains(word)) {
                            containsAnyWord = true;
                            break;
                        }
                    }

                    // Se não encontrar nada aí, tenta no HTML da página URL (se existir)
                    if (!containsAnyWord && !url.isEmpty()) {
                        try {
                            Document doc = Jsoup.connect(url).get();
                            String pageText = doc.text().toLowerCase();

                            for (String word : searchWords) {
                                if (pageText.contains(word)) {
                                    containsAnyWord = true;
                                    break;
                                }
                            }
                        } catch (IOException e) {
                            System.out.println("Erro ao aceder à página: " + url);
                        }
                    }

                    // Adiciona se encontrar correspondência em qualquer parte
                    if (containsAnyWord && !url.isEmpty()) {
                        queue.addFirst(url);
                        System.out.println("Adicionado à queue: " + story.getString("title") + " -> " + url);
                    } else {
                        System.out.println("Este url não contém a pesquisa.");
                    }
                }

                count++;
            }
        } catch (IOException e) {
            throw new RemoteException("Erro ao comunicar com Hacker News API", e);
        }
    }

    /**
     * Interacts with the OpenRouter API to provide AI-assisted responses based on
     * the search results.
     *
     * @param search The search query that generated the search results.
     * @param result The search results to be used in the AI request.
     * @return A string containing the AI-generated response.
     * @throws RemoteException If an error occurs while communicating with the
     *                         OpenRouter API.
     */
    @Override
    public String getAI(String search, List<SearchResult> result) throws RemoteException {
        String apiKey = API_KEY;
        String openRouterUrl = "https://openrouter.ai/api/v1/chat/completions";

        System.out.println("Key starts with: " + apiKey.substring(0, Math.min(apiKey.length(), 15)));

        ArrayList<String> resultString = new ArrayList<>();
        for (SearchResult searchResult : result) {
            // Extract the actual text! Adjust the 'get...' methods to match your class
            String relevantText = searchResult.getTitle() + " - " + searchResult.getUrls();
            resultString.add(relevantText);
        }

        // Prompt atualizada
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content",
                "You are a helpful assistant. I will provide you with several search results or sources related to a specific topic. Your task is to analyze them and produce a clear, concise summary that captures the main insights, trends, or points of interest, highlighting agreements, disagreements, or notable facts in exactly 5 sentences. Please focus on what is most relevant to the topic.\n"
                        +
                        "\nSearch: " + search +
                        "\nSearch results: " + resultString);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject payload = new JsonObject();
        payload.addProperty("model", "openrouter/free");
        payload.add("messages", messages);

        try {
            HttpResponse<String> response = Unirest.post(openRouterUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("HTTP-Referer", "https://example.com")
                    .header("X-Title", "FlawlessRead")
                    .body(payload.toString())
                    .asString();

            if (response.getStatus() != 200) {
                System.out.println("CRITICAL ERROR from OpenRouter!");
                System.out.println("Status Code: " + response.getStatus());
                System.out.println("Response Body: " + response.getBody());
                return "API Error: " + response.getStatus() + " - Check your terminal.";
            }

            Gson gson = new Gson();
            JsonObject jsonResponse = gson.fromJson(response.getBody(), JsonObject.class);

            if (jsonResponse.has("choices")) {
                JsonArray choices = jsonResponse.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        JsonObject messageObj = choice.getAsJsonObject("message");
                        if (messageObj != null && messageObj.has("content")) {
                            String fullSummary = messageObj.get("content").getAsString();
                            return filterLastFiveSentences(fullSummary);
                        }
                    }
                }
            } else {
                System.out.println("Unexpected JSON structure: " + response.getBody());
            }

        } catch (UnirestException e) {
            e.printStackTrace();
        }

        return "No response from AI";
    }

    /**
     * Returns the last five sentences from the input string.
     * <p>
     * This method is used with the {@link #getAi()} method.
     * </p>
     * 
     * @param input the input string
     * @return the last five sentences, or fewer if the input has less than five
     *         sentences
     */
    private String filterLastFiveSentences(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "No text provided.";
        }

        String[] sentences = text.split("(?<=[.!?])\\s+");

        int totalSentences = sentences.length;

        int startIndex = Math.max(0, totalSentences - 5);

        StringBuilder result = new StringBuilder();
        for (int i = startIndex; i < totalSentences; i++) {
            result.append(sentences[i]).append(" ");
        }

        return result.toString().trim();
    }

}
