import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.Scanner;

public class ManagerConsole {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 5001; // Η νέα θύρα λόγω AirPlay conflict στο Mac

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Online Gaming Platform Manager ---");

        while (true) {
            System.out.println("\nSelect Action:");
            System.out.println("1. Add New Game");
            System.out.println("2. View Profits (MapReduce)");
            System.out.println("3. Exit");
            System.out.print("Choice: ");
            
            // 1. Έλεγχος αν η είσοδος είναι αριθμός
            if (!scanner.hasNextInt()) {
                System.out.println("Invalid input! Please enter a number (1, 2, or 3).");
                scanner.next(); // Καθαρισμός του λάθος input (π.χ. αν έγραψες γράμματα)
                continue;
            }
            
            int choice = scanner.nextInt();
            scanner.nextLine(); // Κατανάλωση της αλλαγής γραμμής

            // 2. Έλεγχος αν ο αριθμός είναι έγκυρη επιλογή
            if (choice < 1 || choice > 3) {
                System.out.println("Invalid choice! Please select 1, 2, or 3.");
                continue;
            }

            // Κλήση της αντίστοιχης μεθόδου βάσει επιλογής
            if (choice == 1) {
                addNewGame(scanner);
            } else if (choice == 2) {
                viewProfits();
            } else if (choice == 3) {
                System.out.println("Exiting Manager Console...");
                break;
            }
        }
    }

    private static void addNewGame(Scanner scanner) {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            System.out.println("\n--- Adding New Game ---");
            System.out.print("Enter Game Name: ");
            String name = scanner.nextLine();
            
            System.out.print("Enter Provider: ");
            String provider = scanner.nextLine();

            // Έλεγχος για Stars (1-5)
            int stars;
            while (true) {
                System.out.print("Enter Stars (1-5): ");
                if (scanner.hasNextInt()) {
                    stars = scanner.nextInt();
                    if (stars >= 1 && stars <= 5) {
                        scanner.nextLine(); 
                        break;
                    }
                } else {
                    scanner.next(); 
                }
                System.out.println("Invalid stars! Please enter a number between 1 and 5.");
            }

            // Έλεγχος για Min Bet (για να μην κρασάρει με γράμματα)
            double minBet = 0;
            while (true) {
                System.out.print("Enter Min Bet: ");
                if (scanner.hasNextDouble()) {
                    minBet = scanner.nextDouble();
                    scanner.nextLine(); 
                    break;
                } else {
                    System.out.println("Error: Please enter a numeric value for Bet (e.g., 2.5)");
                    scanner.next(); 
                }
            }

            // Έλεγχος για Risk Level
            String risk = "";
            while (true) {
                System.out.print("Enter Risk Level (low, medium, high): ");
                risk = scanner.nextLine().toLowerCase().trim();
                if (risk.equals("low") || risk.equals("medium") || risk.equals("high")) {
                    break;
                }
                System.out.println("Invalid risk! Choose between: low, medium, high");
            }

            // Δημιουργία και αποστολή του αντικειμένου Game στον Master
            Game newGame = new Game(name, provider, stars, minBet, risk);
            out.writeObject(newGame);
            out.flush();

            System.out.println("Game added successfully and sent to Master!");

        } catch (Exception e) {
            System.err.println("Error adding game: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked") // Καταστολή προειδοποίησης για το cast του Map
    private static void viewProfits() {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            // Αποστολή αιτήματος για στατιστικά στον Master
            out.writeObject("VIEW_PROFITS");
            out.flush();

            // Λήψη του συγκεντρωτικού Map (αποτέλεσμα του Reduce)
            Object response = in.readObject();
            if (response instanceof Map) {
                Map<String, Double> stats = (Map<String, Double>) response;

                System.out.println("\n--- Global Statistics (System Profits/Losses) ---");
                if (stats.isEmpty()) {
                    System.out.println("No betting data available yet.");
                } else {
                    double totalPlatformProfit = 0;
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        System.out.printf("Game: %-15s | Profit: %10.2f tokens\n", 
                                          entry.getKey(), entry.getValue());
                        totalPlatformProfit += entry.getValue();
                    }
                    System.out.println("-------------------------------------------------");
                    System.out.printf("TOTAL PLATFORM PROFIT: %.2f tokens\n", totalPlatformProfit);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching stats: " + e.getMessage());
        }
    }
}