import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.List;

public class DummyPlayerApp {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 9000; // Η νέα θύρα

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Welcome to Online Gaming (Dummy App) ---");

        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            // 1. Αποστολή Φίλτρων (Αναζήτηση)
            System.out.println("Set your filters:");
            
            // Έλεγχος για Stars
            int stars = 0;
            while (true) {
                System.out.print("Min Stars (1-5): ");
                if (scanner.hasNextInt()) {
                    stars = scanner.nextInt();
                    if (stars >= 1 && stars <= 5) break;
                }
                System.out.println("Invalid input! Please enter a number between 1 and 5.");
                scanner.next(); // Καθαρισμός buffer
            }

            // Έλεγχος για Risk
            String risk = "";
            while (true) {
                System.out.print("Risk level (low, medium, high): ");
                risk = scanner.next().toLowerCase();
                if (risk.equals("low") || risk.equals("medium") || risk.equals("high")) break;
                System.out.println("Invalid risk! Use: low, medium, or high.");
            }
            
            SearchFilters filters = new SearchFilters(stars, risk);
            out.writeObject(filters);
            out.flush();

            // 2. Λήψη Αποτελεσμάτων (MapReduce Result)
            System.out.println("Searching for games...");
            Object response = in.readObject();
            if (response instanceof List) {
                List<Game> foundGames = (List<Game>) response;
                
                if (foundGames.isEmpty()) {
                    System.out.println("No games found with these filters.");
                    return;
                }

                System.out.println("Available Games:");
                for (int i = 0; i < foundGames.size(); i++) {
                    System.out.println(i + ". " + foundGames.get(i).gameName);
                }

                // 3. Πραγματοποίηση Πονταρίσματος
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

                PlayRequest playReq = new PlayRequest(foundGames.get(gameIdx).gameName, bet);

                // Νέο socket για το PlayRequest, γιατί ο Master κλείνει τη σύνδεση μετά από κάθε αίτημα
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
                }
            }

        } catch (Exception e) {
            System.err.println("Communication Error: " + e.getMessage());
        }
    }
}