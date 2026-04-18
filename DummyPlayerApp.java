import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Scanner;

public class DummyPlayerApp {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 9000;

    // Shared state between main thread and search thread
    private static List<Game> searchResults = null;
    private static boolean searchDone = false;
    private static boolean searchInProgress = false;
    private static final Object searchLock = new Object();

    public static void main(String[] args) throws InterruptedException {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Welcome to Online Gaming (Dummy App) ---");

        System.out.print("Enter your player ID: ");
        String playerId = scanner.nextLine().trim();

        String lastPlayedGame = null;

        while (true) {
            System.out.println("\n========= MAIN MENU =========");
            System.out.println("1. Search games with filters (async)");
            System.out.println("2. View search results");
            System.out.println("3. Play a game");
            System.out.println("4. Rate last played game");
            System.out.println("5. Exit");

            synchronized (searchLock) {
                if (searchInProgress && !searchDone) {
                    System.out.println("[Search running in background...]");
                } else if (searchDone) {
                    int count = (searchResults != null) ? searchResults.size() : 0;
                    System.out.println("[Last search: " + count + " result(s) ready]");
                }
            }

            System.out.print("Choice: ");
            String line = scanner.nextLine().trim();
            int choice;
            try {
                choice = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Invalid input.");
                continue;
            }

            switch (choice) {
                case 1:
                    SearchFilters filters = collectFilters(scanner, playerId);
                    launchAsyncSearch(filters);
                    break;

                case 2:
                    viewSearchResults();
                    break;

                case 3:
                    String played = playGame(scanner, playerId);
                    if (played != null) lastPlayedGame = played;
                    break;

                case 4:
                    if (lastPlayedGame != null) {
                        rateGame(scanner, lastPlayedGame);
                    } else {
                        System.out.println("You have not played any game yet.");
                    }
                    break;

                case 5:
                    System.out.println("Goodbye!");
                    return;

                default:
                    System.out.println("Invalid choice. Select 1-5.");
            }
        }
    }

    // Collects filters from the user on the main thread (blocking input is fine here)
    private static SearchFilters collectFilters(Scanner scanner, String playerId) {
        System.out.println("\n--- Set Search Filters ---");

        int stars = 0;
        while (true) {
            System.out.print("Min Stars (1-5, or 0 for any): ");
            try {
                stars = Integer.parseInt(scanner.nextLine().trim());
                if (stars >= 0 && stars <= 5) break;
            } catch (NumberFormatException e) {}
            System.out.println("Invalid! Enter a number between 0 and 5.");
        }

        String risk = "";
        while (true) {
            System.out.print("Risk level (low / medium / high / any): ");
            risk = scanner.nextLine().trim().toLowerCase();
            if (risk.equals("low") || risk.equals("medium") || risk.equals("high") || risk.equals("any")) break;
            System.out.println("Invalid! Use: low, medium, high or any.");
        }
        if (risk.equals("any")) risk = "";

        String betCategory = "";
        while (true) {
            System.out.print("Bet category ($  /  $$  /  $$$  / any): ");
            betCategory = scanner.nextLine().trim();
            if (betCategory.equals("$") || betCategory.equals("$$") || betCategory.equals("$$$")
                    || betCategory.equalsIgnoreCase("any")) break;
            System.out.println("Invalid! Use: $, $$, $$$ or any.");
        }
        if (betCategory.equalsIgnoreCase("any")) betCategory = "";

        return new SearchFilters(stars, risk, betCategory, playerId);
    }

    // Spawns a background Thread to perform the TCP search.
    // Main thread returns immediately to the menu — this is the async requirement.
    private static void launchAsyncSearch(SearchFilters filters) {
        synchronized (searchLock) {
            if (searchInProgress && !searchDone) {
                System.out.println("A search is already running in the background. Please wait.");
                return;
            }
            searchInProgress = true;
            searchDone = false;
            searchResults = null;
        }

        Thread searchThread = new Thread(() -> {
            List<Game> results = doSearch(filters);
            synchronized (searchLock) {
                searchResults = results;
                searchDone = true;
                searchInProgress = false;
                searchLock.notifyAll(); // Wake up any thread waiting on option 2
            }
            System.out.println("\n[Background search complete! Select '2' to view results.]");
        });
        searchThread.setDaemon(true);
        searchThread.start();

        // Main thread returns here immediately — menu stays interactive
        System.out.println("Search submitted in background. You can continue using the menu.");
    }

    // If search is still running, main thread waits (via wait()) until notified.
    // If search is already done, results are shown immediately.
    private static void viewSearchResults() throws InterruptedException {
        synchronized (searchLock) {
            if (!searchInProgress && !searchDone) {
                System.out.println("No search submitted yet. Select option 1 first.");
                return;
            }
            if (!searchDone) {
                System.out.println("Waiting for background search to complete...");
                while (!searchDone) {
                    searchLock.wait(); // Released by notifyAll() in search thread
                }
            }
        }

        List<Game> results;
        synchronized (searchLock) {
            results = searchResults;
        }

        if (results == null || results.isEmpty()) {
            System.out.println("No games found with those filters.");
            return;
        }

        System.out.println("\n--- Search Results ---");
        for (int i = 0; i < results.size(); i++) {
            Game g = results.get(i);
            System.out.printf("  %d. %-20s | Stars: %d | Risk: %-6s | MinBet: %.2f | Jackpot: %.0fx%n",
                i, g.gameName, g.stars, g.riskLevel, g.minBet, g.jackpot);
        }
    }

    private static String playGame(Scanner scanner, String playerId) throws InterruptedException {
        List<Game> games;
        synchronized (searchLock) {
            if (!searchDone || searchResults == null || searchResults.isEmpty()) {
                System.out.println("No search results available. Search first (option 1) then view results (option 2).");
                return null;
            }
            games = searchResults;
        }

        System.out.println("\n--- Available Games ---");
        for (int i = 0; i < games.size(); i++) {
            Game g = games.get(i);
            System.out.printf("  %d. %-20s | Stars: %d | Risk: %-6s | MinBet: %.2f | Jackpot: %.0fx%n",
                i, g.gameName, g.stars, g.riskLevel, g.minBet, g.jackpot);
        }

        int gameIdx = -1;
        while (true) {
            System.out.print("Select game index: ");
            try {
                gameIdx = Integer.parseInt(scanner.nextLine().trim());
                if (gameIdx >= 0 && gameIdx < games.size()) break;
            } catch (NumberFormatException e) {}
            System.out.println("Invalid index.");
        }

        Game selected = games.get(gameIdx);

        double bet = 0;
        while (true) {
            System.out.print("Enter bet amount (min " + selected.minBet + "): ");
            try {
                bet = Double.parseDouble(scanner.nextLine().trim());
                if (bet >= selected.minBet) break;
                System.out.println("Bet too low! Minimum: " + selected.minBet);
            } catch (NumberFormatException e) {
                System.out.println("Invalid amount.");
            }
        }

        PlayRequest playReq = new PlayRequest(selected.gameName, bet, playerId);

        try (Socket playSocket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream playOut = new ObjectOutputStream(playSocket.getOutputStream());
             ObjectInputStream playIn = new ObjectInputStream(playSocket.getInputStream())) {

            playOut.writeObject(playReq);
            playOut.flush();

            double result = playIn.readDouble();
            if (result > bet) {
                System.out.println("CONGRATULATIONS! You won: " + result + " tokens!");
            } else if (result > 0) {
                System.out.println("You got back: " + result + " tokens.");
            } else {
                System.out.println("You lost. Better luck next time!");
            }
        } catch (Exception e) {
            System.err.println("Error during play: " + e.getMessage());
            return null;
        }

        return selected.gameName;
    }

    private static void rateGame(Scanner scanner, String gameName) {
        int ratingStars = 0;
        while (true) {
            System.out.print("Enter stars (1-5): ");
            try {
                ratingStars = Integer.parseInt(scanner.nextLine().trim());
                if (ratingStars >= 1 && ratingStars <= 5) break;
            } catch (NumberFormatException e) {}
            System.out.println("Invalid! Enter a number between 1 and 5.");
        }

        try (Socket rateSocket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream rateOut = new ObjectOutputStream(rateSocket.getOutputStream());
             ObjectInputStream rateIn = new ObjectInputStream(rateSocket.getInputStream())) {

            rateOut.writeObject("RATE_GAME:" + gameName + ":" + ratingStars);
            rateOut.flush();

            boolean success = rateIn.readBoolean();
            if (success) {
                System.out.println("Thank you for rating \"" + gameName + "\" with " + ratingStars + " stars!");
            } else {
                System.out.println("Could not submit rating.");
            }
        } catch (Exception e) {
            System.err.println("Error during rating: " + e.getMessage());
        }
    }

    // Runs inside the background Thread — performs the TCP communication with Master
    @SuppressWarnings("unchecked")
    private static List<Game> doSearch(SearchFilters filters) {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject(filters);
            out.flush();

            Object response = in.readObject();
            if (response instanceof List) {
                return (List<Game>) response;
            }
        } catch (Exception e) {
            System.err.println("[Search thread] Error: " + e.getMessage());
        }
        return null;
    }
}
