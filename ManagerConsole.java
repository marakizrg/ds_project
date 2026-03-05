import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.Scanner;

public class ManagerConsole {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 5000;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Online Gaming Platform Manager ---");

        while (true) {
            System.out.println("\nSelect Action:");
            System.out.println("1. Add New Game");
            System.out.println("2. View Profits (MapReduce)");
            System.out.println("3. Exit");
            System.out.print("Choice: ");
            
            int choice = scanner.nextInt();
            scanner.nextLine(); // consume newline

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

            // Διάβασμα στοιχείων για τη δημιουργία νέου παιχνιδιού
            System.out.print("Enter Game Name: ");
            String name = scanner.nextLine();
            System.out.print("Enter Provider: ");
            String provider = scanner.nextLine();
            System.out.print("Enter Min Bet: ");
            double minBet = scanner.nextDouble();
            scanner.nextLine(); // consume newline
            System.out.print("Enter Risk Level (low, medium, high): ");
            String risk = scanner.nextLine();

            // Δημιουργία αντικειμένου Game και αυτόματος υπολογισμός πεδίων
            Game newGame = new Game(name, provider, 3, minBet, risk);
            
            // Ορισμός του κοινού μυστικού S για την ασφαλή επικοινωνία με τον SRG
            newGame.secretKey = "secret123"; 

            // Αποστολή στον Master για αποθήκευση μέσω hashing
            out.writeObject(newGame);
            out.flush();
            System.out.println("Game '" + name + "' sent to Master successfully!");

        } catch (IOException e) {
            System.err.println("Error connecting to Master: " + e.getMessage());
        }
    }

    private static void viewProfits() {
        System.out.println("Requesting statistics from Master (MapReduce in progress)...");
        
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            // Αποστολή αιτήματος για στατιστικά
            out.writeObject("VIEW_PROFITS");
            out.flush();

            // Λήψη του συγκεντρωτικού Map (Reduce result) από τον Master
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