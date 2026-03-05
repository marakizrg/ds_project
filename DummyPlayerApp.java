import java.io.*;
import java.net.*;
import java.util.Scanner;

public class DummyPlayerApp {
    private static final String MASTER_IP = "localhost";
    private static final int MASTER_PORT = 5000;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Welcome to Online Gaming (Dummy App) ---");

        try (Socket socket = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            // 1. Αποστολή Φίλτρων (Αναζήτηση)
            System.out.println("Set your filters:");
            System.out.print("Min Stars (1-5): ");
            int stars = scanner.nextInt();
            System.out.print("Risk level (low, medium, high): ");
            String risk = scanner.next();
            
            // Στέλνουμε ένα αντικείμενο με τα φίλτρα στον Master [cite: 22, 70]
            SearchFilters filters = new SearchFilters(stars, risk);
            out.writeObject(filters);
            out.flush();

            // 2. Λήψη Αποτελεσμάτων (MapReduce Result) [cite: 22, 71]
            System.out.println("Searching for games...");
            Object response = in.readObject();
            if (response instanceof java.util.List) {
                java.util.List<Game> foundGames = (java.util.List<Game>) response;
                System.out.println("Available Games:");
                for (int i = 0; i < foundGames.size(); i++) {
                    System.out.println(i + ". " + foundGames.get(i).gameName);
                }

                // 3. Πραγματοποίηση Πονταρίσματος [cite: 23, 73]
                System.out.print("\nSelect game index to play: ");
                int gameIdx = scanner.nextInt();
                System.out.print("Enter bet amount: ");
                double bet = scanner.nextDouble();

                // Αποστολή αιτήματος Play στον Master [cite: 73]
                PlayRequest playReq = new PlayRequest(foundGames.get(gameIdx).gameName, bet);
                out.writeObject(playReq);
                out.flush();

                // Λήψη αποτελέσματος (Win/Loss) [cite: 144, 145]
                double result = in.readDouble();
                System.out.println("Result: You got " + result + " tokens!");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}