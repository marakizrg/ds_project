import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Random;

public class SRGServer {
    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(4321); // Τυχαία θύρα
        System.out.println("SRG Server started...");
        while (true) {
            Socket workerSocket = server.accept();
            new Thread(() -> handleWorker(workerSocket)).start(); // Πολυνηματικότητα 
        }
    }

    private static void handleWorker(Socket socket) {
        try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
            
            String secret = in.readUTF(); // Λαμβάνει το secret του παιχνιδιού [cite: 82]
            int randomNumber = new Random().nextInt(1000);
            
            // Δημιουργία SHA-256(αριθμός + secret) 
            String combined = randomNumber + secret;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes("UTF-8"));
            
            out.writeInt(randomNumber);
            out.writeObject(hash);
            out.flush();
        } catch (Exception e) { e.printStackTrace(); }
    }
}