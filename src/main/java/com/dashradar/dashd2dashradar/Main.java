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
        try {
            //registerUTXCache();
            //blockService2.createBlock();
            Block lastSavedBlock = blockRepository.findLastBlock();
            /*if (lastSavedBlock == null) {
            BlockDTO block = client.getBlockByHeight(0);
            blockImportService2.processBlock(block);
            lastSavedBlock = blockRepository.findLastBlock();
            }*/

            //System.out.println("lastSavedBlock "+lastSavedBlock.getHeight());
            long startHeight = lastSavedBlock == null ? 0 : lastSavedBlock.getHeight() + 1;
            String previousBlockHash = lastSavedBlock == null ? null : lastSavedBlock.getHash();
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
                    previousBlockHash = block.getHash();
                }
            } catch (Exception ex) {
                System.out.println(ex);
                System.out.println("Blocks processed");
            }
            System.out.println("Filling privatesend types");
            blockImportService2.fillPstypes();
            System.out.println("Creationg previous connections");
            blockImportService2.createPreviousPSConnections();
            //System.out.println("Calculating transaction fees");
            //blockImportService2.calculateTransactionFees();//Not required anymore
            System.out.println("Creating BlockChainTotals");
            blockImportService2.create_BlockChainTotals();
            System.out.println("\ttx_count");
            blockImportService2.tx_count();
            System.out.println("\tinput_count");
            blockImportService2.input_count();
            System.out.println("\toutput_count");
            blockImportService2.output_count();
            System.out.println("\tmixing_100_0_count");
            blockImportService2.mixing100_0_count();
            System.out.println("\tmixing_10_0_count");
            blockImportService2.mixing_10_0_count();
            System.out.println("\tmixing_1_0_count");
            blockImportService2.mixing_1_0_count();
            System.out.println("\tmixing_0_1_count");
            blockImportService2.mixing_0_1_count();
            System.out.println("\tmixing_0_01_count");
            blockImportService2.mixing_0_01_count();
            System.out.println("\tprivatesend_tx_count");
            blockImportService2.privatesend_tx_count();
            System.out.println("\ttotal_block_rewards");
            blockImportService2.total_block_rewards();
            System.out.println("\ttotal_block_size");
            blockImportService2.total_block_size();
            System.out.println("\ttotal_output_volume");
            blockImportService2.total_output_volume();
            System.out.println("\ttotal_transaction_size");
            blockImportService2.total_transaction_size();
            System.out.println("\ttotal_fees");
            blockImportService2.total_fees();
            System.out.println("Creating Days");
            blockImportService2.last_block_of_day();
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
        sessionFactory
                .openSession()
                .query("MATCH (b:BlockChainTotals) WHERE b.height > " + block.getHeight() + " DETACH DELETE b;",
                        new HashMap<String, Object>(), false);
        return block;
    }

}
