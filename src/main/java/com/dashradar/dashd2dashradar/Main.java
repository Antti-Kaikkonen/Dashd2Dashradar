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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

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
    
    
    
    @Bean
    public Client client(@Value("${rpcurl}") String rpcurl, @Value("${rpcuser}") String rpcuser, @Value("${rpcpassword}") String rpcpassword) {
        return new Client(new DashConnector(rpcurl, rpcuser, rpcpassword));
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            createIndexes();
            dayRepository.deleteOrphanedDays();
            //checkForChanges();
            //scheduled tasks only
        };
    }
    
    @Scheduled(initialDelay = 5000, fixedDelay = 50)
    public void checkForChanges() throws IOException {
        try {
            handleNewBlocks();
            handleMempool();
        } catch(Exception e) {
            e.printStackTrace();
            //System.out.println("x");
        }
        
        //1a: Check for new blocks
        //1b: If new blocks -> update blockchaintotals and privatesendtotals. Update day if changed
        //2: Check for mempool change
        //handleMempool();
    }
    
    public void handleNewBlocks() throws IOException {
        String dashdBestBlockHash = client.getBestBlockHash();
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        Long lastDay = dayRepository.lastDay();
        //System.out.println("lastDay:"+lastDay);
        if (neo4jBestBlockHash != null && dashdBestBlockHash.equals(neo4jBestBlockHash)) return;
        Long neo4jHeight = neo4jBestBlockHash == null ? -1 : blockRepository.findBlockHeightByHash(neo4jBestBlockHash);
        for (long height = neo4jHeight+1; height <= client.getBlock(dashdBestBlockHash).getHeight(); height++) {
            System.out.println("processing "+height);
            BlockDTO block = client.getBlockByHeight(height);
            long blockDay = block.getTime()/(60*60*24);
            if (neo4jBestBlockHash != null && !block.getPreviousblockhash().equals(neo4jBestBlockHash)) {//REORG
                System.out.println("Blockchain reorganization detected at height " + height + ".");
                for (int i = 0; i < 5; i++) {
                    System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                }
                Block newTip = processReorg(block.getPreviousblockhash());
                dayRepository.deleteOrphanedDays();
                lastDay = dayRepository.lastDay();
                height = newTip.getHeight();
                neo4jBestBlockHash = newTip.getHash();
                continue;
            }
            if (lastDay == null) lastDay = blockDay-1;
            boolean dayChanged = blockDay > lastDay+1;
            blockImportService.processBlock(block, dayChanged);
            if (dayChanged) { //Date changed
                LocalDate printDay = LocalDate.ofEpochDay(blockDay);
                System.out.println("Day changed to " + printDay);
                lastDay = blockDay-1;
            }
            neo4jBestBlockHash = block.getHash();
        }
    }
    
    @Transactional
    public void handleMempool() throws IOException {
        //List<String> newTxIdCandidates = client.getRawMempool();
        
        Map<String, MempoolTransactionDTO> rawMempoolDetailed = client.getRawMempoolDetailed();
        Set<String> newTxIdCandidates = rawMempoolDetailed.keySet();
        List<String> neo4jMempoolTxids = transactionRepository.getMempoolTxids();
        
        String dashdBestBlockHash = client.getBestBlockHash();
        String neo4jBestBlockHash = blockRepository.findBestBlockHash();
        if (!dashdBestBlockHash.equals(neo4jBestBlockHash)) return;//This avoids saving mempool transactions with referenced outputs missing from the blockchain
        
        newTxIdCandidates = newTxIdCandidates.stream().filter(candidate -> {
            if (neo4jMempoolTxids.contains(candidate)) return false;//Only save once
            boolean dependingTransactionsSaved = rawMempoolDetailed.get(candidate).getDepends().stream().allMatch(depend -> neo4jMempoolTxids.contains(depend));
            return dependingTransactionsSaved;
        }).collect(Collectors.toSet());
        for (String newTxid : newTxIdCandidates) {
            //System.out.println("Adding "+newTxid+" to mempool");
            TransactionDTO tx = client.getTrasactionByTxId(newTxid);
            transactionRepository.createMempoolTransaction(tx.getLocktime(), Transaction.PRIVATE_SEND_NONE, tx.getSize(), tx.getTxid(), tx.getVersion(), System.currentTimeMillis()/1000);
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
            multiInputHeuristicClusterService.clusterizeTransaction(tx.getTxid());

            Transaction tx2 = transactionRepository.findByTxid(tx.getTxid(), 2);
            int psType = TransactionUtil.getPsType(tx2);
            tx2.setPstype(psType);
            if (tx2.getPstype() != Transaction.PRIVATE_SEND_NONE) {
                transactionRepository.save(tx2);
            }
            //balanceEventService.createBalances(tx.getTxid());
        }
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
