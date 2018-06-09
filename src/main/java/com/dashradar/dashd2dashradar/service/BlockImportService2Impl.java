package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.client.Client;
import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO.VIn;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO.VOut;
import com.dashradar.dashradarbackend.domain.Block;
import com.dashradar.dashradarbackend.domain.PrivateSendTotals;
import com.dashradar.dashradarbackend.domain.Transaction;
import com.dashradar.dashradarbackend.repository.BlockChainTotalsRepository;
import com.dashradar.dashradarbackend.repository.BlockRepository;
import com.dashradar.dashradarbackend.repository.DayRepository;
import com.dashradar.dashradarbackend.repository.PrivateSendTotalsRepository;
import com.dashradar.dashradarbackend.repository.TransactionInputRepository;
import com.dashradar.dashradarbackend.repository.TransactionOutputRepository;
import com.dashradar.dashradarbackend.repository.TransactionRepository;
import com.dashradar.dashradarbackend.service.BalanceEventService;
import com.dashradar.dashradarbackend.service.DailyPercentilesService;
import com.dashradar.dashradarbackend.service.MultiInputHeuristicClusterService;
import com.dashradar.dashradarbackend.util.TransactionUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockImportService2Impl implements BlockImportService2 {
    
    @Autowired
    private MultiInputHeuristicClusterService multiInputHeuristicClusterService;
    
    @Autowired
    private TransactionRepository transactionRepository;
    
    @Autowired
    private TransactionOutputRepository transactionOutputRepository;
    
    @Autowired
    private TransactionInputRepository transactionInputRepository;
    
    @Autowired
    private Client client;
    
    @Autowired
    private BlockRepository blockRepository;
    
    @Autowired
    private BalanceEventService balanceEventService;
    
    @Autowired
    private BlockChainTotalsRepository blockChainTotalsRepository;
    
    @Autowired
    private PrivateSendTotalsRepository privateSendTotalsRepository;
    
    @Autowired
    private DayRepository dayRepository;
    
    @Autowired
    private DailyPercentilesService dailyPercentilesService;

    private void createPrivateSendChainTotals(BlockDTO block) {
        privateSendTotalsRepository.compute_mixing_100_0_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_10_0_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_1_0_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_1_counts(block.getHash());
        privateSendTotalsRepository.compute_mixing_0_01_counts(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_tx_count(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_mixing_0_01_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_0_1_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_1_0_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_10_0_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_100_0_output_count(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_mixing_0_01_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_0_1_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_1_0_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_10_0_spent_output_count(block.getHash());
        privateSendTotalsRepository.compute_privatesend_mixing_100_0_spent_output_count(block.getHash());
        
        privateSendTotalsRepository.compute_privatesend_tx_input_count(block.getHash());
        //TODO
    }
    
    private void createBlockChainTotals(BlockDTO block) {
        blockChainTotalsRepository.compute_input_counts(block.getHash());
        blockChainTotalsRepository.compute_output_counts(block.getHash());
        blockChainTotalsRepository.compute_total_block_rewards(block.getHash());
        blockChainTotalsRepository.compute_total_block_size(block.getHash());
        blockChainTotalsRepository.compute_total_fees(block.getHash());
        blockChainTotalsRepository.compute_total_output_volume(block.getHash());
        blockChainTotalsRepository.compute_total_transaction_size(block.getHash());
        blockChainTotalsRepository.compute_total_tx_count(block.getHash());
    }
    
    @Override
    @Transactional
    public void processBlock(BlockDTO block, boolean dayChanged) throws IOException {
        
        if (dayChanged) {
            dayRepository.setLastBlockOfDay(block.getPreviousblockhash());
            for (double percentile = 0.25; percentile < 1; percentile += 0.25) {
                dailyPercentilesService.createDailyPercentiles(block.getTime()/(60*60*24)-1, percentile);
            }
        }
        
        if (block.getHeight() == 0) {
            System.out.println("creating genesis block");
            blockRepository.createGenesisBlock(block.getBits(), block.getChainwork(), block.getDifficulty(), block.getHash(), block.getHeight(), block.getMediantime(), 
                block.getMerkleroot(), block.getNonce(), block.getSize(), block.getTime(), block.getVersion());
            return;
        }
        Boolean unorhanBlock = blockRepository.unorhanBlock(block.getHash());
        if (unorhanBlock != null && unorhanBlock == true) {
            balanceEventService.handleUnorphanedBlock(block.getHash());
            return;
        }
        blockRepository.createEmptyBestBlock(block.getBits(), block.getChainwork(), block.getDifficulty(), block.getHash(), block.getHeight(), block.getMediantime(), 
                block.getMerkleroot(), block.getNonce(), block.getSize(), block.getTime(), block.getVersion());
        
        List<String> mempoolTxids = transactionRepository.getMempoolTxids();
        int n = 0;
        for (String txid : block.getTx()) {
            if (mempoolTxids.contains(txid)) {
                transactionRepository.moveMempooTransactionToBlock(txid, block.getHash(), n);
                //move to block
            } else {
                TransactionDTO tx = client.getTrasactionByTxId(txid);
                transactionRepository.createBlockTransaction(n, tx.getLocktime(), Transaction.PRIVATE_SEND_NONE, tx.getSize(), txid, tx.getVersion(), block.getHash());
                for (VIn vin : tx.getVin()) {
                    if (vin.getCoinbase() != null) {
                        transactionInputRepository.createCoinbaseInput(txid, vin.getSequence(), vin.getCoinbase());
                    } else {
                        transactionInputRepository.createTransactionInput(txid, vin.getSequence(), vin.getTxid(), vin.getVout());
                    }
                }
                for (VOut vout : tx.getVout()) {
                    List<String> addresses;
                    if (vout.getScriptPubKey() != null && vout.getScriptPubKey().getAddresses() != null) {
                        addresses = Arrays.asList(vout.getScriptPubKey().getAddresses());
                    } else {
                        addresses = new ArrayList<>();
                    }
                    transactionOutputRepository.createTransactionOutput(txid, vout.getN(), vout.getValueSat(), addresses);
                }
                if (n > 0) {
                    transactionRepository.compute_tx_fee(txid);
                    multiInputHeuristicClusterService.clusterizeTransaction(txid);

                    Transaction tx2 = transactionRepository.findByTxid(txid, 2);
                    int psType = TransactionUtil.getPsType(tx2);
                    tx2.setPstype(psType);
                    if (tx2.getPstype() != Transaction.PRIVATE_SEND_NONE) {
                        transactionRepository.save(tx2);
                    }
                }
            }
            balanceEventService.createBalances(txid);
            n++;
        }
        
        createBlockChainTotals(block);
        createPrivateSendChainTotals(block);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_100_0);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_10_0);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_1_0);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_1);
        transactionRepository.create_previous_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_01);
        
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_100_0);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_10_0);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_1_0);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_1);
        transactionRepository.create_first_round_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_01);
        
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_100_0);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_10_0);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_1_0);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_1);
        transactionRepository.create_mixing_source_connections(block.getHash(), Transaction.PRIVATE_SEND_MIXING_0_01);
    }

    @Override
    public void processTx(TransactionDTO transaction) {
        multiInputHeuristicClusterService.clusterizeTransaction(transaction.getTxid());
        
    }
      
}
