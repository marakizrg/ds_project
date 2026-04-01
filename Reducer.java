import java.io.*;
import java.net.*;
import java.util.*;

public class Reducer {
    private static final int PORT = 8500;

    public static void main(String[] args) throws IOException {
        new Reducer().startServer();
    }

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
        public void run() {
            try {
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());

                String msg = (String) in.readObject();

                if (msg.equals("REDUCE_SEARCH")) {
                    // Λαμβάνει List<Object> όπου κάθε στοιχείο είναι List<Game> από έναν Worker
                    List<Object> mapResults = (List<Object>) in.readObject();
                    List<Game> combined = new ArrayList<>();
                    for (Object partial : mapResults) {
                        if (partial instanceof List) {
                            combined.addAll((List<Game>) partial);
                        }
                    }
                    out.writeObject(combined);
                    out.flush();
                    System.out.println("[Reducer] REDUCE_SEARCH: combined " + combined.size() + " games.");

                } else if (msg.equals("REDUCE_STATS")) {
                    // Λαμβάνει List<Object> όπου κάθε στοιχείο είναι Map<String,Double> από έναν Worker
                    List<Object> mapResults = (List<Object>) in.readObject();
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
                    System.out.println("[Reducer] REDUCE_STATS: combined " + combined.size() + " entries.");

                } else {
                    System.err.println("[Reducer] Unknown message: " + msg);
                }

            } catch (Exception e) {
                System.err.println("[Reducer] Error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }
}
