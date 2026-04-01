import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.List;

public class DummyPlayerApp {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 9000;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Welcome to Online Gaming (Dummy App) ---");

        System.out.print("Enter your player ID: ");
        String playerId = scanner.next();

        // 1. Εμφάνιση ΟΛΩΝ των διαθέσιμων παιχνιδιών (χωρίς φίλτρα)
        System.out.println("\nFetching all available games...");
        List<Game> allGames = searchGames(new SearchFilters(0, "", "", playerId));
        if (allGames == null || allGames.isEmpty()) {
            System.out.println("No games are currently available on the platform.");
            return;
        }

        System.out.println("\n--- All Available Games ---");
        for (Game g : allGames) {
            System.out.printf("  %-20s | Stars: %d | Risk: %-6s | MinBet: %.2f | Jackpot: %.0fx%n",
                g.gameName, g.stars, g.riskLevel, g.minBet, g.jackpot);
        }

        // 2. Ζήτα φίλτρα
        System.out.println("\n--- Set Filters (press Enter / type 'any' to skip) ---");

        int stars = 0;
        while (true) {
            System.out.print("Min Stars (1-5, or 0 for any): ");
            if (scanner.hasNextInt()) {
                stars = scanner.nextInt();
                if (stars >= 0 && stars <= 5) break;
            }
            System.out.println("Invalid input! Enter a number between 0 and 5.");
            scanner.next();
        }

        String risk = "";
        while (true) {
            System.out.print("Risk level (low, medium, high, or 'any'): ");
            risk = scanner.next().toLowerCase();
            if (risk.equals("low") || risk.equals("medium") || risk.equals("high") || risk.equals("any")) break;
            System.out.println("Invalid risk! Use: low, medium, high or any.");
        }
        if (risk.equals("any")) risk = "";

        String betCategory = "";
        while (true) {
            System.out.print("Bet category ($, $$, $$$ or 'any'): ");
            betCategory = scanner.next().trim();
            if (betCategory.equals("$") || betCategory.equals("$$") || betCategory.equals("$$$")
                    || betCategory.equalsIgnoreCase("any")) break;
            System.out.println("Invalid category! Use: $, $$, $$$ or any.");
        }
        if (betCategory.equalsIgnoreCase("any")) betCategory = "";

        // 3. Αναζήτηση με φίλτρα
        System.out.println("\nSearching with filters...");
        List<Game> foundGames = searchGames(new SearchFilters(stars, risk, betCategory, playerId));

        if (foundGames == null || foundGames.isEmpty()) {
            System.out.println("No games found with these filters.");
            return;
        }

        System.out.println("\n--- Filtered Results ---");
        for (int i = 0; i < foundGames.size(); i++) {
            Game g = foundGames.get(i);
            System.out.printf("  %d. %-20s | Stars: %d | Risk: %-6s | MinBet: %.2f | Jackpot: %.0fx%n",
                i, g.gameName, g.stars, g.riskLevel, g.minBet, g.jackpot);
        }

        // 4. Επιλογή παιχνιδιού
        int gameIdx = -1;
        while (true) {
            System.out.print("\nSelect game index to play: ");
            if (scanner.hasNextInt()) {
                gameIdx = scanner.nextInt();
                if (gameIdx >= 0 && gameIdx < foundGames.size()) break;
            }
            System.out.println("Invalid index! Select a number from the list.");
            scanner.next();
        }

        // 5. Ποντάρισμα
        double bet = 0;
        while (true) {
            System.out.print("Enter bet amount: ");
            if (scanner.hasNextDouble()) {
                bet = scanner.nextDouble();
                if (bet >= foundGames.get(gameIdx).minBet) break;
                System.out.println("Bet too low! Min bet for this game is: " + foundGames.get(gameIdx).minBet);
            } else {
                System.out.println("Invalid input! Please enter a number for the bet.");
                scanner.next();
            }
        }

        String selectedGameName = foundGames.get(gameIdx).gameName;
        PlayRequest playReq = new PlayRequest(selectedGameName, bet, playerId);

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
            return;
        }

        // 6. Αξιολόγηση παιχνιδιού
        System.out.print("Would you like to rate the game? (y/n): ");
        String rateChoice = scanner.next().trim().toLowerCase();
        if (rateChoice.equals("y")) {
            int ratingStars = 0;
            while (true) {
                System.out.print("Enter stars (1-5): ");
                if (scanner.hasNextInt()) {
                    ratingStars = scanner.nextInt();
                    if (ratingStars >= 1 && ratingStars <= 5) break;
                }
                System.out.println("Invalid! Please enter a number between 1 and 5.");
                scanner.next();
            }

            try (Socket rateSocket = new Socket(MASTER_IP, MASTER_PORT);
                 ObjectOutputStream rateOut = new ObjectOutputStream(rateSocket.getOutputStream());
                 ObjectInputStream rateIn = new ObjectInputStream(rateSocket.getInputStream())) {

                rateOut.writeObject("RATE_GAME:" + selectedGameName + ":" + ratingStars);
                rateOut.flush();

                boolean rateSuccess = rateIn.readBoolean();
                if (rateSuccess) {
                    System.out.println("Thank you for rating \"" + selectedGameName + "\" with " + ratingStars + " stars!");
                } else {
                    System.out.println("Could not submit rating.");
                }
            } catch (Exception e) {
                System.err.println("Error during rating: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Game> searchGames(SearchFilters filters) {
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
            System.err.println("Communication Error: " + e.getMessage());
        }
        return null;
    }
}
