package org.example;

import java.io.FileInputStream;
import java.io.IOException;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Scanner;

import org.example.common.*;

/**
 * Main class representing the client interface for interacting with the
 * distributed search engine system.
 * Connects to the Gateway via RMI, provides a command-line interface for user
 * interaction,
 * and displays administrative statistics when requested.
 * 
 * Implements the IStatistics interface to receive real-time updates.
 */
public class Main extends UnicastRemoteObject implements IStatistics {

    private static final String LINE_BREAK = "=".repeat(30);
    private static final String CONFIG_FILE = "frontend/src/main/resources/gateway.properties";

    private IGateway gateway;
    private boolean isAdminPageOpen = false;
    private Scanner scanner;

    private int menuOption;

    /**
     * Default constructor that sets up the scanner for user input.
     * 
     * @throws RemoteException if RMI setup fails
     */
    public Main() throws RemoteException {
        super();
        this.scanner = new Scanner(System.in);
    }

    /**
     * Entry point of the application. Connects to the RMI registry, initializes the
     * client,
     * registers for statistics updates, and handles menu-driven user interaction.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {

        System.out.println(LINE_BREAK);
        System.out.println("Client connecting...");

        Main client = null;

        try {
            client = new Main();

            Properties prop = client.loadProperties();
            String host = prop.getProperty("rmi.host", "localhost");
            int port = Integer.parseInt(prop.getProperty("rmi.port", "1099"));
            String serviceName = prop.getProperty("rmi.service_name", "GatewayService");

            Registry registry = LocateRegistry.getRegistry(host, port);
            client.gateway = (IGateway) registry.lookup(serviceName);
            System.out.println("Connected to Gateway at rmi://" + host + ":" + port + "/" + serviceName);

            // Register the client to receive statistics updates
            client.gateway.registerStatisticsListener(client);
            System.out.println("Client registered for statistics updates.");

        } catch (IOException | NotBoundException e) {
            System.err.println("Failed to connect to Gateway: " + e.getMessage());
        }

        boolean running = true;
        while (running) {
            client.menuOption = client.showMenu();
            if (client.menuOption == 0) {
                client.disconnect();
                running = false;
                break;
            }

            try {
                client.handleMenuOption(client.menuOption);
            } catch (RemoteException e) {
                System.out.println("RMI error: " + e.getMessage());
            }
        }
    }

    /**
     * Displays the administrative statistics page. Continuously fetches and prints
     * system stats
     * until the user chooses to exit.
     */
    private void openAdministrativePage() {
        while (true) {
            try {
                SystemStatistics stats = gateway.getStatistics(); // Request stats from the Gateway
                if (stats != null) {
                    System.out.println(LINE_BREAK + "\nCurrent Statistics:");
                    System.out.println("Top 10 Searches: " + stats.getTopSearches());
                    System.out.println("Active Barrels and Their Sizes: " + stats.getBarrelIndexSizes());
                    System.out.println("Average Response Times per Barrel: " + stats.getAverageResponseTimes());
                } else {
                    System.out.println("No statistics available yet.");
                }
            } catch (RemoteException e) {
                System.out.println("Error fetching statistics: " + e.getMessage());
            }

            System.out.println("\nPress [0] to Exit Administrative Page\n" + LINE_BREAK);

            try {
                if (scanner.hasNextLine()) {
                    String input = scanner.nextLine().trim();
                    if (input.equals("0")) {
                        isAdminPageOpen = false;
                        System.out.println("Exiting Administrative Page...");
                        break;
                    }
                } else {
                    System.out.println("No input detected. Exiting.");
                    break;
                }
            } catch (NoSuchElementException | IllegalStateException e) {
                System.out.println("Input error: " + e.getMessage());
                break;
            }
        }
        isAdminPageOpen = false;
    }

    /**
     * Loads configuration properties from the gateway.properties file.
     *
     * @return Properties loaded from the config file
     * @throws IOException if file cannot be read
     */
    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            properties.load(fis);
        }
        return properties;
    }

    /**
     * Displays the main user menu and returns the selected option.
     *
     * @return selected menu option as an integer
     */
    private int showMenu() {
        int returnValue = -1;

        while (returnValue == -1) {
            System.out.println(LINE_BREAK
                    + "\nMenu:\n"
                    + "[1] Insert URL\n"
                    + "[2] Search\n"
                    + "[3] Consult URL connections\n"
                    + "[4] ChatBot\n"
                    + "[5] Administrative page\n\n"
                    + "[0] Exit\n"
                    + LINE_BREAK);

            String reader = scanner.nextLine().trim();

            try {
                returnValue = Integer.parseInt(reader);
                if (returnValue < 0 || returnValue > 5) {
                    System.out.println("Invalid option. Please choose a valid option.");
                    returnValue = -1;
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number.");
            }
        }
        return returnValue;
    }

    /**
     * Handles user-selected menu actions such as insert, search, consult, or admin
     * view.
     *
     * @param option selected menu option
     * @throws RemoteException if RMI communication fails
     */
    private void handleMenuOption(int option) throws RemoteException {
        switch (option) {
            case 1:
                System.out.println(LINE_BREAK + "\nInsert URL");
                insertURL();
                System.out.println("URL inserted into Queue");
                break;
            case 2:
                System.out.println(LINE_BREAK + "\nSearch");
                performSearch();
                break;
            case 3:
                System.out.println(LINE_BREAK + "\nConsult URL connections");
                consultURLConnections();
                break;
            case 4:
                System.out.println(LINE_BREAK + "\nOpening ChatBot:");
                openChatBot();
                break;
            case 5:
                System.out.println(LINE_BREAK + "\nOpening administrative page:");
                isAdminPageOpen = true;
                openAdministrativePage();
                break;
            default:
                System.out.println("Invalid option.");
        }
    }

    private void openChatBot() {

        ArrayList<String> filteredSearch = null;

        System.out.println("ChatBot Search...");
        String inputChat = scanner.nextLine().trim().toLowerCase();

        try {
            // stopwords = gateway.getStopwords();
            filteredSearch = new ArrayList<>();
        } catch (Exception e) {
            System.out.println(e);
        }

        for (String word : inputChat.split("\\s+")) {
            filteredSearch.add(word);
        }

        try {
            List<SearchResult> freshResults = gateway.search(filteredSearch);

            String answer = gateway.getAI(inputChat, freshResults);
            System.out.println("ChatBot Answer:\n\n" + answer);
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    /**
     * Prompts user to input a URL and inserts it into the system.
     * Asks if the URL should be prioritized for immediate processing.
     */
    private void insertURL() {
        try {
            String url = readURL();

            if (url != null) {
                while (true) {
                    System.out.println("The URL should be processed right now? [y/n]");

                    String input = scanner.nextLine().trim().toUpperCase();

                    if (input.equals("Y")) {
                        gateway.addFirst(url);
                        break;
                    } else if (input.equals("N")) {
                        gateway.insertURL(url);
                        break;
                    } else {
                        System.out.println("Invalid input. Please try again.");
                    }
                }
            }
        } catch (RemoteException e) {
            System.err.println("Failed to insert URL: " + e.getMessage());
        }
    }

    /**
     * <p>
     * Performs a search based on the user's input search query. The method
     * processes the
     * search query by filtering out stopwords, normalizing the text, and sending
     * the filtered
     * terms to the Gateway for searching. The results are then paginated and
     * displayed to the user.
     * The user can navigate through the results pages.
     * </p>
     *
     * <ul>
     * <li>Filters out stopwords (commonly ignored words) before performing the
     * search.</li>
     * <li>Normalizes the search query by removing diacritical marks, punctuation,
     * and converting
     * to lowercase.</li>
     * <li>Allows the user to view search results page by page with navigation
     * options to go to
     * the next or previous page.</li>
     * </ul>
     *
     * <p>
     * If no results are found or if the user inputs an invalid query, appropriate
     * messages are displayed.
     * </p>
     *
     * <p>
     * The method handles RMI exceptions during communication with the Gateway.
     * </p>
     * 
     * @throws RemoteException if there is a communication issue with the Gateway
     *                         during the search process.
     */
    private void performSearch() {
        try {
            String searchQuery = readSearch();
            if (searchQuery == null || searchQuery.isEmpty()) {
                System.out.println("Search query cannot be empty.");
                return;
            }
            searchQuery = normalizeWords(searchQuery);

            List<String> stopwords = gateway.getStopwords();

            String[] search = searchQuery.trim().split("\\s+");

            ArrayList<String> filteredSearch = new ArrayList<>();

            // Iterate over each word in the search query
            for (String word : search) {
                if (!stopwords.contains(word.trim())) {
                    filteredSearch.add(word.trim());
                }
            }

            List<SearchResult> results = gateway.search(filteredSearch);

            if (results.isEmpty()) {
                System.out.println("No results found for: " + searchQuery);
                return;
            }

            int totalResults = results.size();
            int resultsPerPage = 10;
            int totalPages = (int) Math.ceil((double) totalResults / resultsPerPage);
            int currentPage = 1;

            while (true) {
                if (currentPage < 1 || currentPage > totalPages) {
                    System.out.println("No more pages available. Returning to menu.");
                    break;
                }

                int start = (currentPage - 1) * resultsPerPage;
                int end = Math.min(start + resultsPerPage, totalResults);

                System.out.println(LINE_BREAK);
                System.out.println("\nPage " + currentPage + " of " + totalPages + "\n");

                for (int i = start; i < Math.min(end, results.size()); i++) {
                    SearchResult result = results.get(i);
                    System.out.println("URL: " + result.getUrl());
                    System.out.println("Title: " + result.getTitle());
                    System.out.println("Snippet: " + result.getSnippet());
                    System.out.println(LINE_BREAK);
                }

                System.out.println("[P] Previous Page | [N] Next Page | [0] Return to Menu");
                String input = scanner.nextLine().trim().toUpperCase();

                if (input.equals("N")) {
                    if (currentPage < totalPages) {
                        currentPage++;
                    } else {
                        System.out.println("You are already on the last page.");
                    }
                } else if (input.equals("P")) {
                    if (currentPage > 1) {
                        currentPage--;
                    } else {
                        System.out.println("You are already on the first page.");
                    }
                } else if (input.equals("0")) {
                    break;
                } else {
                    System.out.println("Invalid input. Please try again.");
                }
            }

        } catch (RemoteException e) {
            System.err.println("Search failed: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * <p>
     * Prompts the user to input a URL and retrieves a list of connected URLs
     * associated with it.
     * The method validates the URL format before sending a request to the Gateway
     * for connection information.
     * The connections are then printed for the user. The user can return to the
     * menu by entering '0'.
     * </p>
     *
     * <ul>
     * <li>Validates the format of the input URL to ensure it's in the correct
     * format (using regex).</li>
     * <li>If valid, it requests the connected URLs from the Gateway and displays
     * them to the user.</li>
     * <li>If the URL is invalid or no connections are found, appropriate messages
     * are displayed.</li>
     * </ul>
     * 
     * <p>
     * The method handles exceptions that may occur during communication with the
     * Gateway or input processing.
     * </p>
     *
     * @throws RemoteException if there is an error while interacting with the
     *                         Gateway during URL connection retrieval.
     */
    private void consultURLConnections() {
        System.out.println("Input a URL or enter 0 to return:");
        String input = scanner.nextLine().trim();

        if (input.equals("0")) {
            return;
        }

        // Validate the URL input before processing
        if (isValidURL(input)) {
            try {
                // Process the URL input (e.g., search, retrieve data, etc.)
                System.out.println("Processing the URL: " + input);

                SearchResult results = gateway.getConnections(input);
                List<String> urls = results.getUrls();

                if (urls.isEmpty())
                    System.out.println("Results not found");

                for (String url : urls) {
                    System.out.println(url);
                }

            } catch (Exception e) {
                System.out.println("An error occurred while processing the URL.");
                e.printStackTrace();
            }
        } else {
            System.out.println("Invalid URL format. Please enter a valid URL.");
        }
    }

    /**
     * Prompts the user to input a valid URL or exit.
     *
     * @return a valid URL string or null to cancel
     */
    private String readURL() {
        try {
            System.out.println("Input a URL or enter 0 to return:");
            String input = scanner.nextLine().trim();

            if (input.equals("0"))
                return null;

            if (isValidURL(input))
                return input;
            else
                System.out.println("Invalid URL format. Please enter a valid URL.");
        } catch (Exception e) {
            return null;
        }
        return readURL();
    }

    /**
     * Validates the format of a URL string.
     *
     * @param url the URL to validate
     * @return true if the URL is valid, false otherwise
     */
    private boolean isValidURL(String url) {
        String regex = "^(https?|ftp)://[^\s/$.?#].[^\s]*$";
        return url.matches(regex);
    }

    /**
     * Prompts the user to input search terms.
     *
     * @return the entered search string or null on input error
     */
    private String readSearch() {
        try {
            System.out.print("Search terms: ");
            String read = scanner.nextLine();
            return read;
        } catch (NoSuchElementException | IllegalStateException e) {
            return null;
        }
    }

    /**
     * Handles client disconnection and application shutdown.
     */
    private void disconnect() {
        System.out.println(LINE_BREAK);
        System.out.println("Client disconnected successfully.");
        System.exit(0);
    }

    /**
     * <p>
     * Callback method that updates the statistics display on the client when called
     * by the Gateway.
     * The method is triggered to refresh the administrative statistics, displaying
     * current data such as:
     * </p>
     *
     * <ul>
     * <li>Top 10 most searched terms.</li>
     * <li>Active barrels and their sizes.</li>
     * <li>Average response times for each barrel.</li>
     * </ul>
     *
     * <p>
     * The method ensures that statistics are fetched from the Gateway if the
     * administrative page is open,
     * and prints the updated statistics to the console. It also provides a
     * mechanism to gracefully handle any
     * RemoteException that may occur when fetching the statistics.
     * </p>
     *
     * <p>
     * If no statistics are available or if there is an issue with fetching the
     * data, an appropriate message
     * will be displayed to the user.
     * </p>
     *
     * @param stats the system statistics object containing the data to be
     *              displayed.
     * @throws RemoteException if there is a failure during RMI communication while
     *                         retrieving statistics from the Gateway.
     */
    public synchronized void updateStatistics(SystemStatistics stats) throws RemoteException {

        if (isAdminPageOpen) {
            try {
                stats = gateway.getStatistics(); // Request stats from the Gateway
                if (stats != null) {
                    System.out.println(LINE_BREAK + "\nCurrent Statistics:");
                    System.out.println("Top 10 Searches: " + stats.getTopSearches());
                    System.out.println("Active Barrels and Their Sizes: " + stats.getBarrelIndexSizes());
                    System.out.println("Average Response Times per Barrel: " + stats.getAverageResponseTimes());
                } else {
                    System.out.println("No statistics available yet.");
                }
            } catch (RemoteException e) {
                System.out.println("Error fetching statistics: " + e.getMessage());
            }
            System.out.println("\nPress [0] to Exit Administrative Page\n" + LINE_BREAK);
        }
    }

    /**
     * Normalizes input text by removing diacritical marks, punctuation, and
     * converting to lowercase.
     *
     * @param text the input text to normalize
     * @return normalized version of the text
     */
    private String normalizeWords(String text) {
        text = Normalizer.normalize(text, Normalizer.Form.NFD);
        text = text.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        text = text.replaceAll("[\\p{Punct}]", "").toLowerCase();
        return text;
    }
}