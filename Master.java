import java.io.*;
import java.net.*;
import java.util.*;

//class Master
public class Master {
    private static final int PORT = 5001; //η θυρα που ακουει ο master για τα αιτηματα απο manager & players
    
    private List<WorkerInfo> workers = new ArrayList<>(); //λιστα με διαθεσιμους workers

   /* public Master() {
        // Προσθήκη των Workers που θα χρησιμοποιήσει το σύστημα
        workers.add(new WorkerInfo("127.0.0.1", 6001));
        workers.add(new WorkerInfo("127.0.0.1", 6002));
    }
   */  //πρεπει να ορισουμε τους workers δυναμικα οχι να ειναι καρφωτοι
    
   //Main
    public static void main(String[] args) { //ορίζουμε τους Workers δυναμικά, τους παίρνουμε απ τα args της main
        
        Master master = new Master();

        for (String port: args) {
            master.workers.add(new WorkerInfo("127.0.0.1", Integer.parseInt(port)));
        } //end for
        
        master.startServer();

    } //end main

    //τι κανει η μεθοδος αυτη; -> περιμενει να "χτυπησει το τηλεφωνο", το σηκωνει, το δινει σε αλλον να εξυπηρετησει (στο νημα) και περιμενει την επομενη κληση, δηλ λειτουργει σαν τηλεφωνητης
    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) { //δημιουργoυμε τον TCP Server Socket & δεσμευουμε τη θυρα 
            System.out.println("Master Server is running on port " + PORT + "...");
            
            while (true) { //λεει στο προγραμμα να εκτελειται συνεχως, καθως ο μαστερ πρεπει να ειναι 24/7 διαθεσιμος και να περιμενει τον επομενο πελατη
                //αυτη η εντολη ειναι blocking-σταματει εδω το προγραμμα και περιμενει μεχρι να συνδεθει καποιος
                Socket clientSocket = serverSocket.accept(); //αναμονη για νεα συνδεση
                //οταν συνδεθει καποιος το accept ξυπναει το προγραμμα και δημιουργει ενα απλο socket σαν διαυλο επικοινωνιας αποκλειστικα με τον πελατη

                //Η ΠΟΛΥΝΥΜΑΤΙΚΟΤΗΤΑ ΠΟΥ ΖΗΤΑΕΙ Η ΕΡΓΑΣΙΑ ΣΟΣ ΚΟΜΜΑΤΙ ΤΗΣ
                new Thread(new ClientHandler(clientSocket)).start(); //δημιουργια νεα Thread για καθε πελατη
                /*τι γινεται εδω; φτιαχνω ενα νεο νημα για καθε πελατη που συνδεεται, του δινω συνδεση (client socket) για να τον εξυπηρετησει και
                παω πισω στο while για να περιμενω τον νεο πελατη*/

            } //end while

        } catch (IOException e) {
            e.printStackTrace();
        } //end try catch
    } //end startServer

    //κλαση για να διαχειριζομαστε τους πελατες
    //Εδω ο Master καταλαβαινει ποιος του μιλαει και τι πρεπει να κανει
    private class ClientHandler implements Runnable {
        private Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                Object request = in.readObject();

                //1η περιπτωση (manager): προσθηκη νεου παιχνιδιου με hashing
                if (request instanceof Game) { //με το instanceof ο master ξεχωριζει εαν του μιλαει ο manager ή ο player
                    Game newGame = (Game) request;
                   
                    //η πραξη εξασφαλιζει παντα οτι το παιχνιδι "χ" θα πηγαινει παντα στον ιδιο worker
                    int workerIndex = Math.abs(newGame.gameName.hashCode()) % workers.size(); 
                    forwardGameToWorker(newGame, workers.get(workerIndex));
                    System.out.println("Game " + newGame.gameName + " added to worker " + workers.get(workerIndex).port);
                } 
        
                //2η περιπτωση (player): αναζητηση παιχνιδιων mapreduce
                else if (request instanceof SearchFilters) {
                    System.out.println("Starting MapReduce Search...");
                    // Κλήση της μεθόδου MapReduce που υλοποιήσαμε παρακάτω
                    List<Game> finalResults = performMapReduceSearch((SearchFilters) request);
                    out.writeObject(finalResults);
                    out.flush();
                }

                //3η περιπτωση (player): πονταρισμα
                else if (request instanceof PlayRequest) {
                    PlayRequest playReq = (PlayRequest) request;
                    int workerIndex = Math.abs(playReq.gameName.hashCode()) % workers.size(); 
                    double winAmount = forwardPlayToWorker(playReq, workers.get(workerIndex));

                    out.writeDouble(winAmount); 
                    out.flush();
                }
                
                //4η περιπτωση (manager): View Profits (MapReduce Aggregation)
                else if (request instanceof String && request.equals("VIEW_PROFITS")) {
                    Map<String, Double> stats = getGlobalStatistics();
                    out.writeObject(stats);
                    out.flush();
                }

            } catch (Exception e) {
                System.err.println("Master Error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }

    // --- MAPREDUCE: Αναζήτηση Παιχνιδιών ---
    // Αυτό αντικαθιστά τη notifyWorkerForSearch που προκαλούσε το σφάλμα
    private List<Game> performMapReduceSearch(SearchFilters filters) {
        List<Game> finalResults = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
                    
                    out.writeObject(filters); // Φάση MAP
                    out.flush();
                    
                    List<Game> results = (List<Game>) in.readObject();
                    finalResults.addAll(results); // Φάση REDUCE
                } catch (Exception e) {
                    System.err.println("Worker " + worker.port + " is not responding.");
                }
            });
            t.start();
            threads.add(t);
        }

        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { e.printStackTrace(); }
        }
        return finalResults;
    }

    // --- MAPREDUCE: Συγκέντρωση Στατιστικών Κέρδους ---
    private Map<String, Double> getGlobalStatistics() {
        Map<String, Double> globalProfits = new HashMap<>();
        List<Thread> threads = new ArrayList<>();

        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
                    
                    out.writeObject("GET_STATS"); // MAP
                    out.flush();
                    
                    Map<String, Double> workerMap = (Map<String, Double>) in.readObject();
                    synchronized (globalProfits) {
                        for (Map.Entry<String, Double> entry : workerMap.entrySet()) {
                            globalProfits.put(entry.getKey(), globalProfits.getOrDefault(entry.getKey(), 0.0) + entry.getValue());
                        }
                    } // REDUCE
                } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            threads.add(t);
        }

        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { e.printStackTrace(); }
        }
        return globalProfits;
    }

    //στελνει το αντικειμενο game στον σωστο worker για αποθηκευση
    private void forwardGameToWorker(Game game, WorkerInfo worker) throws IOException {
        //ανοιγει socket στην IP & Port του Worker που επιλεχθηκε μεσω hashing 
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())){
            //στελνει το αντικειμενο του παιχνιδιου
            out.writeObject(game);
            out.flush();
        } //end try
    } //end method

    //στελνει τα φιλτρα αναζητησης σε ολους τους workers για να αρχισει η διαδικασια του mapreduce
    private double forwardPlayToWorker(PlayRequest req, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {

            out.writeObject(req);
            out.flush();
            
            // ΠΡΟΣΟΧΗ: Περιμένουμε το double από τον Worker
            return in.readDouble(); 
        } catch (IOException e) {
            System.err.println("Error communicating with worker during play: " + e.getMessage());
            return -1.0; // Επιστροφή λάθους
        }
    }
}