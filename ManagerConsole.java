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
            System.out.println("1. Add New Game (from JSON file)");
            System.out.println("2. View Profits by Game (MapReduce)");
            System.out.println("3. View Profits by Provider");
            System.out.println("4. View Profits for Player");
            System.out.println("5. View All Player Profits");
            System.out.println("6. Remove Game");
            System.out.println("7. Modify Game Risk Level");
            System.out.println("8. Exit");
            System.out.print("Choice: ");

            if (!scanner.hasNextInt()) {
                System.out.println("Invalid input! Please enter a number (1-8).");
                scanner.next();
                continue;
            }

            int choice = scanner.nextInt();
            scanner.nextLine();

            if (choice < 1 || choice > 8) {
                System.out.println("Invalid choice! Please select 1-8.");
                continue;
            }

            if (choice == 1) {
                addNewGame(scanner);
            } else if (choice == 2) {
                viewProfits();
            } else if (choice == 3) {
                viewProfitsByProvider();
            } else if (choice == 4) {
                viewProfitsByPlayer(scanner);
            } else if (choice == 5) {
                viewAllPlayerProfits();
            } else if (choice == 6) {
                removeGame(scanner);
            } else if (choice == 7) {
                modifyGameRisk(scanner);
            } else {
                System.out.println("Exiting Manager Console...");
                break;
            }
        }
    }

    // Simple JSON parser helpers (no external libraries, case-insensitive keys)
    private static String extractJsonString(String json, String key) {
        String jsonLower = json.toLowerCase();
        String search = "\"" + key.toLowerCase() + "\"";
        int keyIdx = jsonLower.indexOf(search);
        if (keyIdx < 0) return "";
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return "";
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return "";
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return "";
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static double extractJsonNumber(String json, String key) {
        String jsonLower = json.toLowerCase();
        String search = "\"" + key.toLowerCase() + "\"";
        int keyIdx = jsonLower.indexOf(search);
        if (keyIdx < 0) return 0.0;
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return 0.0;
        // skip whitespace
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'
                || json.charAt(start) == '\n' || json.charAt(start) == '\r')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static void addNewGame(Scanner scanner) {
        System.out.print("\nEnter path to JSON game file: ");
        String filePath = scanner.nextLine().trim();

        // Read file content
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return;
        }

        String json = sb.toString();

        String name      = extractJsonString(json, "GameName");
        String provider  = extractJsonString(json, "ProviderName");
        String logoPath  = extractJsonString(json, "GameLogo");
        String risk      = extractJsonString(json, "RiskLevel");
        String secretKey = extractJsonString(json, "HashKey");
        int stars        = (int) extractJsonNumber(json, "Stars");
        int noOfVotes    = (int) extractJsonNumber(json, "NoOfVotes");
        double minBet    = extractJsonNumber(json, "MinBet");
        double maxBet    = extractJsonNumber(json, "MaxBet");

        if (name.isEmpty() || provider.isEmpty() || risk.isEmpty() || secretKey.isEmpty()) {
            System.err.println("Error: JSON file is missing required fields (gameName, providerName, riskLevel, secretKey).");
            return;
        }
        if (logoPath.isEmpty()) logoPath = "N/A";
        if (stars < 1 || stars > 5) { System.err.println("Error: stars must be 1-5."); return; }
        if (minBet <= 0) { System.err.println("Error: minBet must be positive."); return; }
        if (maxBet < minBet) { System.err.println("Error: maxBet must be >= minBet."); return; }

        Game newGame = new Game(name, provider, stars, noOfVotes, logoPath, minBet, maxBet, risk, secretKey);

        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
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

                System.out.println("\n--- Global Statistics (System Profits/Losses by Game) ---");
                if (stats.isEmpty()) {
                    System.out.println("No betting data available yet.");
                } else {
                    double totalPlatformProfit = 0;
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        System.out.printf("Game: %-20s | Profit: %10.2f tokens\n",
                                          entry.getKey(), entry.getValue());
                        totalPlatformProfit += entry.getValue();
                    }
                    System.out.println("----------------------------------------------------------");
                    System.out.printf("TOTAL PLATFORM PROFIT: %.2f tokens\n", totalPlatformProfit);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching stats: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void viewProfitsByProvider() {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VIEW_PROFITS_PROVIDER");
            out.flush();

            Object response = in.readObject();
            if (response instanceof Map) {
                Map<String, Double> stats = (Map<String, Double>) response;

                System.out.println("\n--- Profits by Provider ---");
                if (stats.isEmpty()) {
                    System.out.println("No betting data available yet.");
                } else {
                    double total = 0;
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        System.out.printf("Provider: %-20s | Profit: %10.2f tokens\n",
                                          entry.getKey(), entry.getValue());
                        total += entry.getValue();
                    }
                    System.out.println("----------------------------------------------------------");
                    System.out.printf("TOTAL: %.2f tokens\n", total);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching provider stats: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void viewProfitsByPlayer(Scanner scanner) {
        System.out.print("\nEnter Player ID: ");
        String playerId = scanner.nextLine().trim();

        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VIEW_PROFITS_PLAYER:" + playerId);
            out.flush();

            Object response = in.readObject();
            if (response instanceof Map) {
                Map<String, Double> stats = (Map<String, Double>) response;

                System.out.println("\n--- Platform Net Loss for Player: " + playerId + " ---");
                if (stats.isEmpty()) {
                    System.out.println("No data for player: " + playerId);
                } else {
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        // Positive value = platform profit (player's net loss)
                        System.out.printf("Player: %-15s | Net Loss (platform profit): %10.2f tokens\n",
                                          entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching player stats: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void viewAllPlayerProfits() {
        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VIEW_ALL_PLAYERS");
            out.flush();

            Object response = in.readObject();
            if (response instanceof Map) {
                Map<String, Double> stats = (Map<String, Double>) response;

                System.out.println("\n--- All Player Profits/Losses (from platform perspective) ---");
                if (stats.isEmpty()) {
                    System.out.println("No player data available yet.");
                } else {
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        System.out.printf("Player: %-15s | Net Loss: %10.2f tokens\n",
                                          entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching all player stats: " + e.getMessage());
        }
    }
}
