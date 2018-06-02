package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.client.Client;
import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO.VIn;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO.VOut;
import com.dashradar.dashradarbackend.domain.Transaction;
import com.dashradar.dashradarbackend.repository.BlockRepository;
import com.dashradar.dashradarbackend.repository.TransactionInputRepository;
import com.dashradar.dashradarbackend.repository.TransactionOutputRepository;
import com.dashradar.dashradarbackend.repository.TransactionRepository;
import com.dashradar.dashradarbackend.service.BalanceEventService;
import com.dashradar.dashradarbackend.service.MultiInputHeuristicClusterService;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
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

    private void createPrivateSendChainTotals(BlockDTO block) {
        //TODO
    }
    
    private void createBlockChainTotals(BlockDTO block) {
        //TODO
    }
    
    @Override
    @Transactional
    public void processBlock(BlockDTO block) throws IOException {
        
        if (block.getHeight() == 0) {
            blockRepository.createGenesisBlock(block.getBits(), block.getChainwork(), block.getDifficulty(), block.getHash(), block.getHeight(), block.getMediantime(), 
                block.getMerkleroot(), block.getNonce(), block.getSize(), block.getTime(), block.getVersion());
            return;
        }
        blockRepository.createEmptyBestBlock(block.getBits(), block.getChainwork(), block.getDifficulty(), block.getHash(), block.getHeight(), block.getMediantime(), 
                block.getMerkleroot(), block.getNonce(), block.getSize(), block.getTime(), block.getVersion());
        
        List<String> mempoolTxids = transactionRepository.getMempoolTxids();
        int n = 0;
        for (String txid : block.getTx()) {
            if (mempoolTxids.contains(txid)) {
                transactionRepository.moveMempooTransactionToBlock(txid, block.getHash());
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
                    transactionOutputRepository.createTransactionOutput(txid, vout.getN(), vout.getValueSat(), Arrays.asList(vout.getScriptPubKey().getAddresses()));
                }
            }
            multiInputHeuristicClusterService.clusterizeTransaction(txid);
            balanceEventService.createBalances(txid);
            n++;
        }
        
        createBlockChainTotals(block);
        createPrivateSendChainTotals(block);
    }

    @Override
    public void processTx(TransactionDTO transaction) {
        multiInputHeuristicClusterService.clusterizeTransaction(transaction.getTxid());
        
    }
    
}
