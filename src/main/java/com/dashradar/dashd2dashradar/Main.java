package com.dashradar.dashd2dashradar;

import com.dashradar.dashdhttpconnector.client.Client;
import com.dashradar.dashdhttpconnector.client.DashConnector;
import com.dashradar.dashradarbackend.domain.Block;
import com.dashradar.dashradarbackend.repository.BlockRepository;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashradarbackend.config.PersistenceContext;
import java.util.HashMap;
import org.neo4j.ogm.session.SessionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import com.dashradar.dashd2dashradar.service.BlockImportService;
import com.dashradar.dashdhttpconnector.dto.MempoolTransactionDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import com.dashradar.dashradarbackend.domain.Transaction;
import com.dashradar.dashradarbackend.repository.BlockChainTotalsRepository;
import com.dashradar.dashradarbackend.repository.DayRepository;
import com.dashradar.dashradarbackend.repository.PrivateSendTotalsRepository;
import com.dashradar.dashradarbackend.repository.TransactionInputRepository;
import com.dashradar.dashradarbackend.repository.TransactionOutputRepository;
import com.dashradar.dashradarbackend.repository.TransactionRepository;
import com.dashradar.dashradarbackend.service.BalanceEventService;
import com.dashradar.dashradarbackend.service.DailyPercentilesService;
import com.dashradar.dashradarbackend.service.MultiInputHeuristicClusterService;
import com.dashradar.dashradarbackend.util.TransactionUtil;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.zeromq.ZMQ;

@Component
@Configuration
@EnableAutoConfiguration
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.dashradar.dashd2dashradar", exclude = {Neo4jDataAutoConfiguration.class})
@Import({PersistenceContext.class})
public class Main {

    public static void main(String[] args) throws IOException {
        SpringApplication.run(Main.class, args);
    }

    @Autowired
    private BlockImportService blockImportService;

    @Autowired
    private BlockRepository blockRepository;

    @Autowired
    private Client client;

    @Autowired
    private SessionFactory sessionFactory;
    
    @Autowired
    private BlockChainTotalsRepository blockChainTotalsRepository;
    
    @Autowired
    private PrivateSendTotalsRepository privateSendTotalsRepository;
    
    @Autowired
    private MultiInputHeuristicClusterService multiInputHeuristicClusterService;
    
    @Autowired
    private BalanceEventService balanceEventService;
    
    @Autowired
    private DailyPercentilesService dailyPercentilesService;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @Autowired
    private DayRepository dayRepository;
    
    @Autowired
    private TransactionInputRepository transactionInputRepository;
    
    @Autowired
    private TransactionOutputRepository transactionOutputRepository;
    
    @Value("${zmqaddress}") String zmqaddress;
    
    private Set<String> unsavedTxLocks = ConcurrentHashMap.newKeySet();//contains txids
    
    @Bean
    public Client client(@Value("${rpcurl}") String rpcurl, @Value("${rpcuser}") String rpcuser, @Value("${rpcpassword}") String rpcpassword) {
        return new Client(new DashConnector(rpcurl, rpcuser, rpcpassword));
    }
    
    boolean ready = false;

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            boolean add_ps_collateral_types = false;
            boolean add_mixing_sizes = false;
            boolean add_difficulty = false;
            for (String arg : args) {
                if (arg.equals("add_mixing_sizes")) {
                    add_mixing_sizes = true;
                }
                if (arg.equals("add_create_ps_collateral_types")) {
                    add_ps_collateral_types = true;
                }
                if (arg.equals("add_difficulty")) {
                    add_difficulty = true;
                }
            }
            createIndexes();
            dayRepository.deleteOrphanedDays();
            if (add_difficulty) {
                System.out.println("Adding difficulty");
                addDifficulty();
            }
            if (add_ps_collateral_types) {
                System.out.println("adding collateral ps types!");
                addPsCollateralTypes();
            }
            if (add_mixing_sizes) {
                System.out.println("adding mixing sizes!");
                addMixingSizes();
                
            }
            ready = true;
            
            instantSendLoop();
        };
    }
    
    private void addDifficulty() throws IOException {
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        long toHeight = client.getBlock(neo4jBestBlockHash).getHeight();
        for (long height = 1; height <= toHeight; height++) {
            System.out.println("h: "+height);
            String blockHash = client.getBlockHash(height);
            blockChainTotalsRepository.compute_total_difficulty(blockHash);
        }
    }
    
    private void addMixingSizes() throws IOException {
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        long toHeight = client.getBlock(neo4jBestBlockHash).getHeight();
        for (long height = 1; height <= toHeight; height++) {
            System.out.println("h: "+height);
            String blockHash = client.getBlockHash(height);
            privateSendTotalsRepository.compute_mixing_100_0_size(blockHash);
            privateSendTotalsRepository.compute_mixing_10_0_size(blockHash);
            privateSendTotalsRepository.compute_mixing_1_0_size(blockHash);
            privateSendTotalsRepository.compute_mixing_0_1_size(blockHash);
            privateSendTotalsRepository.compute_mixing_0_01_size(blockHash);
        }
    }
    
    private void addPsCollateralTypes() throws IOException {
        int blockInterval = 10;
        String dashdBestBlockHash = client.getBestBlockHash();
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        long toHeight = client.getBlock(dashdBestBlockHash).getHeight();
        Session openSession = sessionFactory.openSession();
        for (long height = 0; height <= toHeight; height+=blockInterval) {
            System.out.println("Adding collateral types height:"+height);
            HashMap<String, Object> params = new HashMap<>();
            params.put("minHeight", height);
            params.put("maxHeight", height+blockInterval);
            Result possiblyCollateralPayment = openSession.query(
"CYPHER planner = rule\n" +
"MATCH (b:Block)\n" +
"WHERE b.height >= $minHeight AND b.height < $maxHeight\n" +
"WITH b\n"+
"MATCH (tx:Transaction {pstype:0})-[:INCLUDED_IN]->(b)\n" +
"WHERE tx.n > 0 AND (tx.feesSat = "+TransactionUtil.COLLATERAL_PAYMENT+" OR tx.feesSat = "+TransactionUtil.COLLATERAL_PAYMENT_LEGACY+") \n" +
"RETURN tx.txid as txid;", params);
            for (Map<String, Object> row : possiblyCollateralPayment.queryResults()) {
                String txid = (String) row.get("txid");
                TransactionDTO tx = client.getTrasactionByTxId(txid);
                int psType = blockImportService.getPsType(tx);
                if (psType != Transaction.PRIVATE_SEND_NONE) {
                    HashMap<String, Object> params2 = new HashMap<>();
                    params2.put("txid", txid);
                    params2.put("pstype", psType);
                    openSession.query("MATCH (tx:Transaction {txid:$txid}) SET tx.pstype=$pstype;", params2);
                }
            }
            Result possiblyMakeCollateralInputs = openSession.query(
"CYPHER planner = rule\n" +
"MATCH (b:Block)\n" +
"WHERE b.height >= $minHeight AND b.height < $maxHeight\n" +
"WITH b\n"+
"MATCH (output:TransactionOutput)<-[:OUTPUT]-(tx:Transaction {pstype:0})-[:INCLUDED_IN]->(b) \n" +
"WHERE tx.n > 0 AND (output.valueSat="+TransactionUtil.COLLATERAL_OUTPUT+" OR output.valueSat="+TransactionUtil.COLLATERAL_OUTPUT_LEGACY+") \n" +
"RETURN distinct tx.txid as txid;", params);
            for (Map<String, Object> row : possiblyMakeCollateralInputs.queryResults()) {
                String txid = (String) row.get("txid");
                TransactionDTO tx = client.getTrasactionByTxId(txid);
                int psType = blockImportService.getPsType(tx);
                if (psType != Transaction.PRIVATE_SEND_NONE) {
                    HashMap<String, Object> params2 = new HashMap<>();
                    params2.put("txid", txid);
                    params2.put("pstype", psType);
                    openSession.query("MATCH (tx:Transaction {txid:$txid}) SET tx.pstype=$pstype;", params2);
                }
            }
        }
        List<String> mempoolTxids = transactionRepository.getMempoolTxids();
        for (String txid : mempoolTxids) {
            TransactionDTO tx = client.getTrasactionByTxId(txid);
            int psType = blockImportService.getPsType(tx);
            if (psType == Transaction.PRIVATE_SEND_COLLATERAL_PAYMENT || psType == Transaction.PRIVATE_SEND_MAKE_COLLATERAL_INPUTS) {
                HashMap<String, Object> params2 = new HashMap<>();
                params2.put("txid", txid);
                params2.put("pstype", psType);
                openSession.query("MATCH (tx:Transaction {txid:$txid}) SET tx.pstype=$pstype;", params2);
            }
        }
    }
    
    public void instantSendLoop() {
        ZMQ.Context context = ZMQ.context(1);
        ZMQ.Socket subscriber = context.socket(ZMQ.SUB);
        subscriber.connect(zmqaddress);
        subscriber.subscribe("hashtxlock");
        while (!Thread.currentThread ().isInterrupted ()) {
            // Read envelope with address
            String address = subscriber.recvStr();
            String hash = Hex.encodeHexString(subscriber.recv());
            byte[] seq = subscriber.recv();
            ArrayUtils.reverse(seq);//convert little endian to big endian
            long sequence = new BigInteger(seq).longValue();
            if (address.equals("hashtxlock")) {
                Boolean set = transactionRepository.setTxLockIfExists(hash);
                if (set == null || set == false) {
                    unsavedTxLocks.add(hash);
                }
            }
        }
        subscriber.close();
        context.term();
    }
    
    @Scheduled(initialDelay = 5000, fixedDelay = 50)
    public void checkForChanges() throws InterruptedException {
        if (!ready) return;
        try {
            handleNewBlocks();
            Set<String> savedMempoolTxids = handleMempool();
            for (String txid : unsavedTxLocks) {
                Boolean set = transactionRepository.setTxLockIfExists(txid);
                if (set != null && set == true) {
                    unsavedTxLocks.remove(txid);
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
            System.out.println("Waiting 10 seconds before retry...");
            Thread.sleep(10000);
        }
   
    }
    
    
    public void handleNewBlocks() throws IOException {
        String dashdBestBlockHash = client.getBestBlockHash();
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        if (neo4jBestBlockHash != null && dashdBestBlockHash.equals(neo4jBestBlockHash)) return;
        Long neo4jHeight = neo4jBestBlockHash == null ? -1 : blockRepository.findBlockHeightByHash(neo4jBestBlockHash);
        
        BlockDTO block = client.getBlockByHeight(neo4jHeight+1);
        
        if (neo4jBestBlockHash != null && !block.getPreviousblockhash().equals(neo4jBestBlockHash)) {//REORG
            System.out.println("Blockchain reorganization detected at height " + block.getHeight() + ".");
            Block newTip = processReorg(block.getPreviousblockhash());
            dayRepository.deleteOrphanedDays();
            return;
        }
        
        Long lastDay = dayRepository.lastDay();
        String currentHash = block.getHash();
        do {
            long blockDay = block.getTime()/(60*60*24);
            if (lastDay == null) lastDay = blockDay-1;
            boolean dayChanged = blockDay > lastDay+1;
            blockImportService.processBlock(block, dayChanged);
            for (String txid : block.getTx()) {
                if (unsavedTxLocks.contains(txid)) {
                    transactionRepository.setTxLockIfExists(txid);
                    unsavedTxLocks.remove(txid);
                }
            }
            if (dayChanged) { //Date changed
                LocalDate printDay = LocalDate.ofEpochDay(blockDay);
                System.out.println("Day changed to " + printDay);
                lastDay = blockDay-1;
            }
            neo4jBestBlockHash = block.getHash();
            currentHash = block.getNextblockhash();
            if (currentHash != null) block = client.getBlock(currentHash);
        } while (currentHash != null);
    }
    
    
    @Transactional
    public Set<String> handleMempool() throws IOException {
        //List<String> newTxIdCandidates = client.getRawMempool();
        
        Map<String, MempoolTransactionDTO> rawMempoolDetailed = client.getRawMempoolDetailed();
        Set<String> newTxIdCandidates = rawMempoolDetailed.keySet();
        List<String> neo4jMempoolTxids = transactionRepository.getMempoolTxids();
        
        String dashdBestBlockHash = client.getBestBlockHash();
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        if (!dashdBestBlockHash.equals(neo4jBestBlockHash)) return new HashSet<>();//This avoids saving mempool transactions with referenced outputs missing from the blockchain
        
        newTxIdCandidates = newTxIdCandidates.stream().filter(candidate -> {
            if (neo4jMempoolTxids.contains(candidate)) return false;//Only save once
            boolean dependingTransactionsSaved = rawMempoolDetailed.get(candidate).getDepends().stream().allMatch(depend -> neo4jMempoolTxids.contains(depend));
            return dependingTransactionsSaved;
        }).collect(Collectors.toSet());
        for (String newTxid : newTxIdCandidates) {
            //System.out.println("Adding "+newTxid+" to mempool");
            TransactionDTO tx = client.getTrasactionByTxId(newTxid);
            int psType = blockImportService.getPsType(tx);
            transactionRepository.createMempoolTransaction(tx.getLocktime(), psType, tx.getSize(), tx.getTxid(), tx.getVersion(), System.currentTimeMillis()/1000);
            for (TransactionDTO.VIn vin : tx.getVin()) {
                transactionInputRepository.createTransactionInput(tx.getTxid(), vin.getSequence(), vin.getTxid(), vin.getVout());
            }
            for (TransactionDTO.VOut vout : tx.getVout()) {
                List<String> addresses;
                if (vout.getScriptPubKey() != null && vout.getScriptPubKey().getAddresses() != null) {
                    addresses = Arrays.asList(vout.getScriptPubKey().getAddresses());
                } else {
                    addresses = new ArrayList<>();
                }
                transactionOutputRepository.createTransactionOutput(tx.getTxid(), vout.getN(), vout.getValueSat(), addresses);
            }
            transactionRepository.compute_tx_fee(tx.getTxid());

            if (psType != Transaction.PRIVATE_SEND_MIXING_0_01 && psType != Transaction.PRIVATE_SEND_MIXING_0_1 && 
                                psType != Transaction.PRIVATE_SEND_MIXING_1_0 && psType != Transaction.PRIVATE_SEND_MIXING_10_0 && 
                                psType != Transaction.PRIVATE_SEND_MIXING_100_0) {
                multiInputHeuristicClusterService.clusterizeTransaction(tx.getTxid());
            }
            //balanceEventService.createBalances(tx.getTxid());
        }
        return newTxIdCandidates;
    }

    public void createIndexes() {
        System.out.println("CREATING INDEXES");
        HashMap<String, Object> params = new HashMap<>();
        sessionFactory.openSession().query("CREATE INDEX ON :Block(time);", params);
        sessionFactory.openSession().query("CREATE INDEX ON :Block(height);", params);
        sessionFactory.openSession().query("CREATE INDEX ON :Block(hash);", params);
        sessionFactory.openSession().query("CREATE INDEX ON :Address(address);", params);
        //sessionFactory.openSession().query("CREATE INDEX ON :BlockChainTotals(height);", params);
        //sessionFactory.openSession().query("CREATE INDEX ON :BlockChainTotals(time);", params);
        sessionFactory.openSession().query("CREATE INDEX ON :Transaction(feesSat);", params);
        sessionFactory.openSession().query("CREATE INDEX ON :Transaction(pstype);", params);
        sessionFactory.openSession().query("CREATE INDEX ON :Transaction(txid);", params);
    }
    
    @Transactional
    public Block processReorg(String reorghash) throws IOException {
        Block block = blockRepository.findBlockByHash(reorghash);
        String currentHash = reorghash;
        while (block == null) {
            currentHash = client.getBlock(currentHash).getPreviousblockhash();
            block = blockRepository.findBlockByHash(currentHash);
        }
        //blockRepository.deleteSubsequentBlocks(block.getHash());//TODO change to orphaned blocks and transctions
        blockRepository.orphanSubsequentBlocks(block.getHash());
        balanceEventService.handleOrphanedBlocks();
        //balanceEventService.setLastBlockContainingBalanceEvent(block.getHeight());
//        sessionFactory//TODO: is this required anymore? (deleteSubsequentBlocks already does this)
//                .openSession()
//                .query("MATCH (b:BlockChainTotals) WHERE b.height > " + block.getHeight() + " DETACH DELETE b;",
//                        new HashMap<String, Object>(), false);
        return block;
    }

}
