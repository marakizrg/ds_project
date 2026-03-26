import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.Scanner;

public class ManagerConsole {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 9000;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Online Gaming Platform Manager ---");

        while (true) {
            System.out.println("\nSelect Action:");
            System.out.println("1. Add New Game");
            System.out.println("2. View Profits (MapReduce)");
            System.out.println("3. Remove Game");
            System.out.println("4. Modify Game Risk Level");
            System.out.println("5. Exit");
            System.out.print("Choice: ");

            if (!scanner.hasNextInt()) {
                System.out.println("Invalid input! Please enter a number (1-5).");
                scanner.next();
                continue;
            }

            int choice = scanner.nextInt();
            scanner.nextLine();

            if (choice < 1 || choice > 5) {
                System.out.println("Invalid choice! Please select 1-5.");
                continue;
            }

            if (choice == 1) {
                addNewGame(scanner);
            } else if (choice == 2) {
                viewProfits();
            } else if (choice == 3) {
                removeGame(scanner);
            } else if (choice == 4) {
                modifyGameRisk(scanner);
            } else {
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

            int stars;
            while (true) {
                System.out.print("Enter Stars (1-5): ");
                if (scanner.hasNextInt()) {
                    stars = scanner.nextInt();
                    if (stars >= 1 && stars <= 5) { scanner.nextLine(); break; }
                } else { scanner.next(); }
                System.out.println("Invalid stars! Please enter a number between 1 and 5.");
            }

            int noOfVotes;
            while (true) {
                System.out.print("Enter Number of Votes: ");
                if (scanner.hasNextInt()) {
                    noOfVotes = scanner.nextInt();
                    if (noOfVotes >= 0) { scanner.nextLine(); break; }
                } else { scanner.next(); }
                System.out.println("Invalid input! Please enter a non-negative number.");
            }

            System.out.print("Enter Game Logo Path (or press Enter to skip): ");
            String logoPath = scanner.nextLine().trim();
            if (logoPath.isEmpty()) logoPath = "N/A";

            double minBet = 0;
            while (true) {
                System.out.print("Enter Min Bet: ");
                if (scanner.hasNextDouble()) {
                    minBet = scanner.nextDouble();
                    if (minBet > 0) { scanner.nextLine(); break; }
                    System.out.println("Min bet must be positive.");
                } else {
                    System.out.println("Error: Please enter a numeric value (e.g., 2.5)");
                    scanner.next();
                }
            }

            double maxBet = 0;
            while (true) {
                System.out.print("Enter Max Bet: ");
                if (scanner.hasNextDouble()) {
                    maxBet = scanner.nextDouble();
                    if (maxBet >= minBet) { scanner.nextLine(); break; }
                    System.out.println("Max bet must be >= min bet (" + minBet + ").");
                } else {
                    System.out.println("Error: Please enter a numeric value.");
                    scanner.next();
                }
            }

            String risk = "";
            while (true) {
                System.out.print("Enter Risk Level (low, medium, high): ");
                risk = scanner.nextLine().toLowerCase().trim();
                if (risk.equals("low") || risk.equals("medium") || risk.equals("high")) break;
                System.out.println("Invalid risk! Choose between: low, medium, high");
            }

            System.out.print("Enter Secret Key (HashKey): ");
            String secretKey = scanner.nextLine().trim();

            Game newGame = new Game(name, provider, stars, noOfVotes, logoPath, minBet, maxBet, risk, secretKey);
            out.writeObject(newGame);
            out.flush();

            System.out.println("Game \"" + name + "\" added successfully! [betCategory=" + newGame.betCategory + ", jackpot=" + newGame.jackpot + "]");

        } catch (Exception e) {
            System.err.println("Error adding game: " + e.getMessage());
        }
    }

    private static void removeGame(Scanner scanner) {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            System.out.print("\nEnter Game Name to remove: ");
            String name = scanner.nextLine().trim();

            out.writeObject("REMOVE_GAME:" + name);
            out.flush();
            System.out.println("Remove request sent for: " + name);

        } catch (Exception e) {
            System.err.println("Error removing game: " + e.getMessage());
        }
    }

    private static void modifyGameRisk(Scanner scanner) {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            System.out.print("\nEnter Game Name to modify: ");
            String name = scanner.nextLine().trim();

            String newRisk = "";
            while (true) {
                System.out.print("Enter new Risk Level (low, medium, high): ");
                newRisk = scanner.nextLine().toLowerCase().trim();
                if (newRisk.equals("low") || newRisk.equals("medium") || newRisk.equals("high")) break;
                System.out.println("Invalid risk! Choose between: low, medium, high");
            }

            out.writeObject("MODIFY_RISK:" + name + ":" + newRisk);
            out.flush();

            boolean success = in.readBoolean();
            if (success) {
                System.out.println("Game \"" + name + "\" risk level updated to: " + newRisk);
            } else {
                System.out.println("Error: Game \"" + name + "\" not found on the platform.");
            }

        } catch (Exception e) {
            System.err.println("Error modifying game: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void viewProfits() {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VIEW_PROFITS");
            out.flush();

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
