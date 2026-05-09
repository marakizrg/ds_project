import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;

// παράγει τυχαίους αριθμούς και τους αποθηκεύει σε κεντρικό buffer
// κάθε Worker ζητάει έναν αριθμό και τον παίρνει από τον buffer — δεν παράγεται νέος αριθμός κάθε φορά
// όταν ο buffer αδειάσει, ο Producer τον ξαναγεμίζει
public class SRGServer {
    private static final int PORT        = 4321;
    private static final int BUFFER_SIZE = 50;

    private static final Queue<Integer> buffer = new LinkedList<>();
    private static final Object bufferLock = new Object();

    public static void main(String[] args) throws IOException {
        new Thread(SRGServer::runProducer).start(); // ξεκινάει τον Producer που γεμίζει τον buffer

        ServerSocket server = new ServerSocket(PORT);
        System.out.println("SRG Server started on port " + PORT + "...");

        while (true) {
            Socket workerSocket = server.accept();
            new Thread(() -> handleWorker(workerSocket)).start();
        }
    }

    // γεμίζει τον buffer μέχρι BUFFER_SIZE — όταν αδειάσει εντελώς, ξαναγεμίζει
    private static void runProducer() {
        Random rng = new Random();
        while (true) {
            synchronized (bufferLock) {
                // αν ο buffer είναι γεμάτος, περιμένω να αδειάσει
                while (buffer.size() >= BUFFER_SIZE) {
                    try { bufferLock.wait(); } catch (InterruptedException e) {}
                }
                buffer.add(rng.nextInt(1000));
                bufferLock.notifyAll(); // ειδοποιώ τυχόν Workers που περιμένουν αριθμό
            }
        }
    }

    // εξυπηρετεί έναν Worker: παίρνει αριθμό από τον buffer, υπολογίζει hash και τα στέλνει
    private static void handleWorker(Socket socket) {
        try (ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            String secret = in.readUTF(); // το secretKey του παιχνιδιού — χρησιμοποιείται στο hash

            int num;
            synchronized (bufferLock) {
                // αν ο buffer είναι άδειος, περιμένω τον Producer να βάλει αριθμό
                while (buffer.isEmpty()) {
                    try { bufferLock.wait(); } catch (InterruptedException e) {}
                }
                num = buffer.poll();
                bufferLock.notifyAll(); // ειδοποιώ τον Producer ότι υπάρχει ελεύθερη θέση
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((num + secret).getBytes("UTF-8")); // SHA-256(αριθμός + secret)

            out.writeInt(num);
            out.writeObject(hash); // στέλνω και τα δύο — ο Worker θα επαληθεύσει τοπικά
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
