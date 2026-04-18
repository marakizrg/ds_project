import java.io.*;
import java.net.*;
import java.util.*;

// ο Reducer server ενώνει τα μερικά αποτελέσματα από όλους τους Workers σε ένα τελικό
public class Reducer {
    private static final int PORT = 8500;

    public static void main(String[] args) throws IOException {
        new Reducer().startServer();
    }

    // ξεκινάει τον server και δέχεται συνδέσεις κάθε αίτημα το χειρίζεται ενα ξεχωριστό thread
    public void startServer() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Reducer Server running on port " + PORT + "...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ConnectionHandler(clientSocket)).start();
            }
        }
    }

    private class ConnectionHandler implements Runnable {
        private final Socket socket;
        ConnectionHandler(Socket s) { this.socket = s; }

        @Override
        @SuppressWarnings("unchecked")
        // χειρίζεται ένα αίτημα reduce για δυο λογους: αναζήτηση παιχνιδιών ή στατιστικά κερδών
        public void run() {
            try {
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                String msg = (String) in.readObject(); // τι τύπου reduce ζητάει ο Master

                if (msg.equals("REDUCE_SEARCH")) {
                    // ενώνει τις λίστες παιχνιδιών από κάθε Worker σε μία ενιαία λίστα
                    List<Object> mapResults = (List<Object>) in.readObject(); // μία List<Game> ανά Worker
                    List<Game> combined = new ArrayList<>();

                    for (Object partial : mapResults) {
                        if (partial instanceof List) {
                            combined.addAll((List<Game>) partial);
                        }
                    }

                    out.writeObject(combined);
                    out.flush();
                    System.out.println("[Reducer] REDUCE_SEARCH: συνδύασε " + combined.size() + " παιχνίδια.");

                } else if (msg.equals("REDUCE_STATS")) {
                    // αθροίζει τα κέρδη/ζημιές ανά κλειδί (παιχνίδι η παίκτης) από όλους τους Workers
                    List<Object> mapResults = (List<Object>) in.readObject(); // ένα Map<String,Double> ανά Worker
                    Map<String, Double> combined = new HashMap<>();

                    for (Object partial : mapResults) {
                        if (partial instanceof Map) {
                            Map<String, Double> workerMap = (Map<String, Double>) partial;
                            for (Map.Entry<String, Double> e : workerMap.entrySet()) {
                                combined.put(e.getKey(),
                                    combined.getOrDefault(e.getKey(), 0.0) + e.getValue());
                            }
                        }
                    }

                    out.writeObject(combined);
                    out.flush();
                    System.out.println("[Reducer] REDUCE_STATS: συνδύασε " + combined.size() + " εγγραφές.");

                } else {
                    System.err.println("[Reducer] Άγνωστο μήνυμα: " + msg);
                }

            } catch (Exception e) {
                System.err.println("[Reducer] Σφάλμα: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }
}
