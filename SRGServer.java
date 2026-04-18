import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Random;

// παράγει τυχαίους αριθμούς και τους στέλνει στους Workers μαζί με SHA-256 hash
// έτσι ωστε ο Worker μπορεί να ελέγξει ότι ο αριθμός δεν αλλοιώθηκε στη μεταφορά
public class SRGServer {
    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(4321);
        System.out.println("SRG Server started on port 4321...");

        while (true) {
            Socket workerSocket = server.accept();
            new Thread(() -> handleWorker(workerSocket)).start();
        }
    }

    // εξυπηρετεί έναν Worker: παράγει τυχαίο αριθμό, υπολογίζει το hash του και τα στέλνει
    private static void handleWorker(Socket socket) {
        try (ObjectInputStream in   = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            String secret = in.readUTF(); // το secretKey του παιχνιδιού το οποιο χρησιμοποιείται στο hash

            int randomNumber = new Random().nextInt(1000); // τυχαίος αριθμός στο [0, 999]

            String combined = randomNumber + secret;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes("UTF-8")); // SHA-256(αριθμός + secret)

            out.writeInt(randomNumber);
            out.writeObject(hash); // στέλνω και τα δύο ο Worker θα επαληθεύσει τοπικά
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
