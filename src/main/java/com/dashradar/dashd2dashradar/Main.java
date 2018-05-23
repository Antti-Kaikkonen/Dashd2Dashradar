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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.dashradar.dashd2dashradar.service.BlockImportService;
import com.dashradar.dashradarbackend.repository.BlockChainTotalsRepository;
import com.dashradar.dashradarbackend.repository.PrivateSendTotalsRepository;
import com.dashradar.dashradarbackend.service.BalanceEventService;
import com.dashradar.dashradarbackend.service.DailyPercentilesService;
import com.dashradar.dashradarbackend.service.MultiInputHeuristicClusterService;

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
    private BlockImportService blockImportService2;

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

    @Bean
    public Client client(@Value("${rpcurl}") String rpcurl, @Value("${rpcuser}") String rpcuser, @Value("${rpcpassword}") String rpcpassword) {
        return new Client(new DashConnector(rpcurl, rpcuser, rpcpassword));
    }

    @Bean
    public CommandLineRunner commandLineRunner(ApplicationContext ctx) {
        return args -> {
            //scheduled tasks only
        };
    }

    @Scheduled(fixedDelay = 1000 * 60 * 1)
    public void processBlockChain() throws IOException {
        
        int psConnectionsEvery = 50;
        try {
            Block lastSavedBlock = blockRepository.findLastBlock();
            long startHeight = lastSavedBlock == null ? 0 : lastSavedBlock.getHeight() + 1;
   
            Long lastHeightContainingBalanceEvent = balanceEventService.lastBlockContainingBalanceEvent();
            for (long balanceEventHeight = lastHeightContainingBalanceEvent == null ? 0 : lastHeightContainingBalanceEvent+1; balanceEventHeight < startHeight; balanceEventHeight++) {
                System.out.println("asd "+balanceEventHeight);
                balanceEventService.createBalances(balanceEventHeight);
            }
            
            for (long clusterizeHeight = Math.max(1, startHeight - 1 - psConnectionsEvery); clusterizeHeight <= startHeight; clusterizeHeight++) {
                multiInputHeuristicClusterService.clusteerizeBlock(clusterizeHeight);
            }
            blockImportService2.fillPstypes();
            blockImportService2.createPreviousPSConnections(startHeight-1-psConnectionsEvery);
            
            String previousBlockHash = lastSavedBlock == null ? null : lastSavedBlock.getHash();
            long lastHeight = startHeight-1;
            try {
                for (long height = startHeight; height < 900000; height++) {
                    BlockDTO block = client.getBlockByHeight(height);
                    if (previousBlockHash != null && !block.getPreviousblockhash().equals(previousBlockHash)) {
                        //REORG
                        System.out.println("Blockchain reorganization detected at height " + height + ".");
                        Block newTip = processReorg(block.getPreviousblockhash());
                        height = newTip.getHeight();
                        previousBlockHash = newTip.getHash();
                        continue;
                    }

                    System.out.println("height" + height);
                    blockImportService2.processBlock(block);
                    balanceEventService.createBalances(height);
                    if (height % psConnectionsEvery == 0) {
                        blockImportService2.fillPstypes();
                        blockImportService2.createPreviousPSConnections(height-psConnectionsEvery);
                        for (long clusterizeHeight = Math.max(1, height - psConnectionsEvery); clusterizeHeight <= height; clusterizeHeight++) {
                            multiInputHeuristicClusterService.clusteerizeBlock(clusterizeHeight);
                            
                        }
                    }    
                    previousBlockHash = block.getHash();
                    lastHeight = height;
                }
            } catch (Exception ex) {
                System.out.println(ex);
                System.out.println("Blocks processed");
            }
            System.out.println("Filling privatesend types");
            blockImportService2.fillPstypes();
            System.out.println("Creationg previous connections");
            blockImportService2.createPreviousPSConnections(lastHeight);
            System.out.println("Creating BlockChainTotals");
            blockChainTotalsRepository.create_block_chain_totals();
            privateSendTotalsRepository.create_privatesend_totals();
            System.out.println("\ttx_count");
            blockChainTotalsRepository.compute_total_tx_count();
            System.out.println("\tinput_count");
            blockChainTotalsRepository.compute_input_counts();
            System.out.println("\toutput_count");
            blockChainTotalsRepository.compute_output_counts();
            System.out.println("\tmixing_100_0_count");
//            blockChainTotalsRepository.compute_mixing_100_0_counts();
            privateSendTotalsRepository.compute_mixing_100_0_counts();
            System.out.println("\tmixing_10_0_count");
//            blockChainTotalsRepository.compute_mixing_10_0_counts();
            privateSendTotalsRepository.compute_mixing_10_0_counts();
            System.out.println("\tmixing_1_0_count");
//            blockChainTotalsRepository.compute_mixing_1_0_counts();
            privateSendTotalsRepository.compute_mixing_1_0_counts();
            System.out.println("\tmixing_0_1_count");
//            blockChainTotalsRepository.compute_mixing_0_1_counts();
            privateSendTotalsRepository.compute_mixing_0_1_counts();
            System.out.println("\tmixing_0_01_count");
//            blockChainTotalsRepository.compute_mixing_0_01_counts();
            privateSendTotalsRepository.compute_mixing_0_01_counts();
            System.out.println("\tprivatesend_tx_count");
//            blockChainTotalsRepository.compute_privatesend_tx_count();
            privateSendTotalsRepository.compute_privatesend_tx_count();
            System.out.println("\tprivatesend_mixing_output_counts");
            privateSendTotalsRepository.compute_privatesend_mixing_0_01_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_0_1_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_1_0_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_10_0_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_100_0_output_count();
            System.out.println("\tprivatesend_mixing_spent_output_counts");
            privateSendTotalsRepository.compute_privatesend_mixing_0_01_spent_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_0_1_spent_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_1_0_spent_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_10_0_spent_output_count();
            privateSendTotalsRepository.compute_privatesend_mixing_100_0_spent_output_count();
            System.out.println("\tprivate_tx_input_count");
            privateSendTotalsRepository.compute_privatesend_tx_input_count();
            System.out.println("\ttotal_block_rewards");
            blockChainTotalsRepository.compute_total_block_rewards();
            System.out.println("\ttotal_block_size");
            blockChainTotalsRepository.compute_total_block_size();
            System.out.println("\ttotal_output_volume");
            blockChainTotalsRepository.compute_total_output_volume();
            System.out.println("\ttotal_transaction_size");
            blockChainTotalsRepository.compute_total_transaction_size();
            System.out.println("\ttotal_fees");
            blockChainTotalsRepository.compute_total_fees();
            System.out.println("Creating Days");
            blockImportService2.last_block_of_day();
            System.out.println("Creating daily medians");
            for (double percentile = 0.25; percentile < 1; percentile += 0.25) {
                dailyPercentilesService.createMissingDailyPercentiles(percentile);
            }
            System.out.println("Done");
        } catch (Exception ex) {
            System.out.println("Error in scheduled task");
            ex.printStackTrace();
        }
    }

    public void processBlockChain2() throws IOException {
        Block previousBlock = blockRepository.findLastBlock();
        long startHeight = previousBlock == null ? 1 : previousBlock.getHeight() + 1;
        for (long height = startHeight; height < 900000; height++) {
            BlockDTO oldBlock = client.getBlockByHeight(height);
        }
    }

    public Block processReorg(String reorghash) throws IOException {
        Block block = blockRepository.findBlockByHash(reorghash);
        String currentHash = reorghash;
        while (block == null) {
            currentHash = client.getBlock(currentHash).getPreviousblockhash();
            block = blockRepository.findBlockByHash(currentHash);
        }
        blockRepository.deleteSubsequentBlocks(block.getHash());
        balanceEventService.setLastBlockContainingBalanceEvent(block.getHeight());
        sessionFactory//TODO: is this required anymore? (deleteSubsequentBlocks already does this)
                .openSession()
                .query("MATCH (b:BlockChainTotals) WHERE b.height > " + block.getHeight() + " DETACH DELETE b;",
                        new HashMap<String, Object>(), false);
        return block;
    }

}
