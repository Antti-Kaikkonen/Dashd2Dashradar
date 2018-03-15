package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.client.Client;
import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import com.dashradar.dashradarbackend.domain.Transaction;
import com.dashradar.dashradarbackend.repository.TransactionRepository;
import com.dashradar.dashradarbackend.util.TransactionUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockImportServiceImpl implements BlockImportService {

    @Autowired
    private Client client;

    @Autowired
    private SessionFactory sessionFactory;

    @Autowired
    private TransactionRepository transcationRepository;

    @Override
    public void calculateTransactionFees() {
        boolean modifications;
        do {
            Session session = sessionFactory.openSession();
            Map<String, Object> params = new HashMap<>();
            String query
                    = "MATCH \n"
                    + "	(spent_output:TransactionOutput)-[:SPENT_IN]->(:TransactionInput)-[:INPUT]->(tx:Transaction)\n"
                    + "WHERE \n"
                    + "	NOT exists(tx.feesSat)\n"
                    + "WITH \n"
                    + "	tx, \n"
                    + "	sum(spent_output.valueSat) as inSats\n"
                    + "LIMIT 100000\n"
                    + "MATCH \n"
                    + "	(tx)-[:OUTPUT]->(output:TransactionOutput)\n"
                    + "WITH\n"
                    + "	tx,\n"
                    + "	inSats-sum(output.valueSat) as fee\n"
                    + "SET\n"
                    + "	tx += {\n"
                    + "		feesSat: fee\n"
                    + "	}";
            modifications = session.query(query, params).queryStatistics().containsUpdates();
        } while (modifications);
    }

    @Override
    public void createPreviousPSConnections() {
        ArrayList<Integer> pstypes = new ArrayList<>();
        pstypes.add(Transaction.PRIVATE_SEND_MIXING_0_01);
        pstypes.add(Transaction.PRIVATE_SEND_MIXING_0_1);
        pstypes.add(Transaction.PRIVATE_SEND_MIXING_1_0);
        pstypes.add(Transaction.PRIVATE_SEND_MIXING_10_0);
        pstypes.add(Transaction.PRIVATE_SEND_MIXING_100_0);
        for (int pstype : pstypes) {
            Session session = sessionFactory.openSession();
            Map<String, Object> params = new HashMap<>();
            params.put("pstype", pstype);
            String previous_ps_connections
                    = "MATCH \n"
                    + "	(tx1:Transaction {pstype:$pstype})<-[:INPUT]-(i:TransactionInput)-[:SPENT_IN]-(o:TransactionOutput)-[:OUTPUT]-(tx2:Transaction {pstype:$pstype}) \n"
                    + "WITH \n"
                    + "	tx1, \n"
                    + "	tx2, \n"
                    + "	count(o) as rel_count \n"
                    + "WHERE \n"
                    + "	NOT (tx1)-[:PREVIOUS_ROUND]->(tx2) \n"
                    + "CREATE \n"
                    + "	(tx1)-[:PREVIOUS_ROUND {connections:rel_count}]->(tx2);";
            session.query(previous_ps_connections, params);
        }
    }

    /**
     *
     */
    @Override
    public void create_BlockChainTotals() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH\n"
                + "	(b:Block)\n"
                + "WITH\n"
                + "	b\n"
                + "ORDER BY \n"
                + "	b.height\n"
                + "MERGE \n"
                + "	(a:BlockChainTotals {height: b.height})\n"
                + "ON CREATE SET \n"
                + "	a += {\n"
                + "		time: b.time\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void fillPstypes() {
        long start = System.currentTimeMillis();
        int count = 0;
        System.out.println("Filling pstypes for potentially mixing transactions");
        while (fillPageOfMixingPsTypes()) {
            count = count + 70;
            System.out.println(1000.0 * count / (System.currentTimeMillis() - start) + " transactions per second");
        }
        System.out.println("Filling pstypes for potentially privatesend transactions");
        while (fillPageOfPrivateSendPsTypes()) {
            count = count + 70;
            System.out.println(1000.0 * count / (System.currentTimeMillis() - start) + " transactions per second");
        }
    }

    @Override
    public void input_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.input_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(:Transaction)<-[:INPUT]-(input:TransactionInput)\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(input) as input_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	input_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		input_count: coalesce(previousTotals.input_count, 0) + input_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void last_block_of_day() {
        Session session = sessionFactory.openSession();
        org.neo4j.ogm.transaction.Transaction neo4jtransaction = session.beginTransaction(org.neo4j.ogm.transaction.Transaction.Type.READ_WRITE);
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH (b:BlockChainTotals)\n"
                + "WITH b.time/86400 as day, max(b.height) as last_height\n"
                + "MATCH (lastBlock:BlockChainTotals {height:last_height}) \n"
                + "MERGE (d:Day {day:day})-[:LAST_BLOCK]->(lastBlock)";
        session.query(query, params);

        String deleteQuery = "MATCH (n:Day) WITH max(n.day) as last_day MATCH (lastDay:Day {day:last_day}) DETACH DELETE lastDay;";
        session.query(deleteQuery, params);
        neo4jtransaction.commit();
        neo4jtransaction.close();
    }

    @Override
    public void mixing100_0_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.privatesend_mixing_100_0_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction {pstype:3})\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		privatesend_mixing_100_0_count: coalesce(previousTotals.privatesend_mixing_100_0_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void mixing_0_01_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.privatesend_mixing_0_01_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction {pstype:7})\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		privatesend_mixing_0_01_count: coalesce(previousTotals.privatesend_mixing_0_01_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void mixing_0_1_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.privatesend_mixing_0_1_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction {pstype:6})\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		privatesend_mixing_0_1_count: coalesce(previousTotals.privatesend_mixing_0_1_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void mixing_10_0_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.privatesend_mixing_10_0_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction {pstype:4})\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		privatesend_mixing_10_0_count: coalesce(previousTotals.privatesend_mixing_10_0_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void mixing_1_0_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.privatesend_mixing_1_0_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction {pstype:5})\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		privatesend_mixing_1_0_count: coalesce(previousTotals.privatesend_mixing_1_0_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void output_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.output_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(:Transaction)-[:OUTPUT]->(output:TransactionOutput)\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(output) as output_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	output_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		output_count: coalesce(previousTotals.output_count, 0) + output_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void privatesend_tx_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.privatesend_tx_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction {pstype:2})\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		privatesend_tx_count: coalesce(previousTotals.privatesend_tx_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void processBlock(BlockDTO block) throws IOException {
        // BlockDTO block = client.getBlockByHeight(height);
        //if (block.getPreviousblockhash() !== null && block.getPreviousblockhash() == )
        Session session = sessionFactory.openSession();
        boolean firstBlock = block.getPreviousblockhash() == null;
        String query = createQuery2(firstBlock);
        Map<String, Object> params = createParams2(block);
        boolean readonly = false;
        session.query(query, params, readonly);
    }

    @Override
    public void total_block_rewards() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.total_block_rewards_sat)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction)-[:OUTPUT]->(output:TransactionOutput), (tx)<-[:INPUT]-(input:TransactionInput)\n"
                + "WHERE\n"
                + "	input.coinbase IS NOT NULL\n"
                + "WITH\n"
                + "	a,\n"
                + "	sum(distinct output.valueSat) as total_block_rewards_sat\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	total_block_rewards_sat,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		total_block_rewards_sat: coalesce(previousTotals.total_block_rewards_sat, 0) + total_block_rewards_sat\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void total_block_size() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.total_block_size)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})\n"
                + "WITH\n"
                + "	a,\n"
                + "	b.size as block_size\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	block_size,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		total_block_size: coalesce(previousTotals.total_block_size, 0) + block_size\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void total_fees() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE \n"
                + "	NOT exists(a.total_fees_sat)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction)\n"
                + "WITH\n"
                + "	a,\n"
                + "	sum(tx.feesSat) as fees\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	fees,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		total_fees_sat: coalesce(previousTotals.total_fees_sat, 0) + fees\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void total_output_volume() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.total_output_volume_sat)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(:Transaction)-[:OUTPUT]->(output:TransactionOutput)\n"
                + "WITH\n"
                + "	a,\n"
                + "	sum(output.valueSat) as total_output_volume_sat\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	total_output_volume_sat,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		total_output_volume_sat: coalesce(previousTotals.total_output_volume_sat, 0) + total_output_volume_sat\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void total_transaction_size() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.total_transaction_size)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction)\n"
                + "WITH\n"
                + "	a,\n"
                + "	sum(tx.size) as total_transaction_size\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	total_transaction_size,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		total_transaction_size: coalesce(previousTotals.total_transaction_size, 0) + total_transaction_size\n"
                + "	}";
        session.query(query, params);
    }

    @Override
    public void tx_count() {
        Session session = sessionFactory.openSession();
        Map<String, Object> params = new HashMap<>();
        String query
                = "MATCH \n"
                + "	(a:BlockChainTotals)\n"
                + "WHERE\n"
                + "	NOT exists(a.tx_count)\n"
                + "WITH\n"
                + "	a\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH \n"
                + "	(b:Block {height: a.height})<-[:INCLUDED_IN]-(tx:Transaction)\n"
                + "WITH\n"
                + "	a,\n"
                + "	count(tx) as tx_count\n"
                + "ORDER BY \n"
                + "	a.height\n"
                + "OPTIONAL MATCH\n"
                + "	(previousTotals:BlockChainTotals {height: a.height-1})\n"
                + "WITH\n"
                + "	a,\n"
                + "	tx_count,\n"
                + "	previousTotals\n"
                + "ORDER BY\n"
                + "	a.height\n"
                + "SET\n"
                + "	a += {\n"
                + "		tx_count: coalesce(previousTotals.tx_count, 0) + tx_count\n"
                + "	}";
        session.query(query, params);
    }

    private Map<String, Object> coinbaseTxToParams2(TransactionDTO transactionDTO) {
        Map<String, Object> transaction = new HashMap<>();
        Map<String, Object> transactionProperties = new HashMap<>();
        ArrayList<Object> transactionInputs = new ArrayList<>();
        ArrayList<Object> transactionOutputs = new ArrayList<>();
        transaction.put("properties", transactionProperties);
        transaction.put("outputs", transactionOutputs);
        transaction.put("inputs", transactionInputs);
        transactionProperties.put("n", 0);
        transactionProperties.put("locktime", transactionDTO.getLocktime());
        transactionProperties.put("pstype", 0);
        transactionProperties.put("size", transactionDTO.getSize());
        transactionProperties.put("txid", transactionDTO.getTxid());
        transactionProperties.put("version", transactionDTO.getVersion());
        for (TransactionDTO.VOut vout : transactionDTO.getVout()) {
            Map<String, Object> transactionOutput = new HashMap<>();
            Map<String, Object> transactionOutputProperties = new HashMap<>();
            transactionOutput.put("properties", transactionOutputProperties);
            transactionOutputProperties.put("n", vout.getN());
            //transactionOutputProperties.put("value", vout.getValue());
            transactionOutputProperties.put("valueSat", vout.getValueSat());

            Map<String, Object> scriptPubKey = new HashMap<>();
            transactionOutput.put("scriptPubKey", scriptPubKey);
            if (vout.getScriptPubKey() != null && vout.getScriptPubKey().getAddresses() != null) {
                scriptPubKey.put("addresses", vout.getScriptPubKey().getAddresses());
            } else {
                scriptPubKey.put("addresses", new String[0]);
            }

            transactionOutputs.add(transactionOutput);
        }
        for (TransactionDTO.VIn vin : transactionDTO.getVin()) {
            Map<String, Object> transactionInput = new HashMap<>();
            Map<String, Object> transactionInputProperties = new HashMap<>();
            transactionInput.put("properties", transactionInputProperties);
            transactionInputProperties.put("sequence", vin.getSequence());

            transactionInputProperties.put("coinbase", vin.getCoinbase());

            transactionInputs.add(transactionInput);
        }
        transaction.put("outputs", transactionOutputs);
        transaction.put("inputs", transactionInputs);
        return transaction;
    }

    private long computeTentativePstype(TransactionDTO transactionDTO) {
        List<Long> nonDenoms = transactionDTO.getVout().stream().map(vout -> vout.getValueSat()).filter(vout -> !TransactionUtil.isDenomination(vout)).collect(Collectors.toList());
        boolean hasDenomOutput = nonDenoms.size() < transactionDTO.getVout().size();
        boolean allOutputsAreDenoms = nonDenoms.isEmpty();
        boolean allOutputsAreSame = transactionDTO.getVout().stream().map(vout -> vout.getValueSat()).distinct().limit(2).count() <= 1;

        boolean hasCollateralOutput = nonDenoms.stream().anyMatch(valueSat -> TransactionUtil.isCollateralOutput(valueSat));

        boolean potentiallyMixing = transactionDTO.getVin().size() == transactionDTO.getVout().size() && transactionDTO.getVout().size() >= 3 && allOutputsAreDenoms && allOutputsAreSame;
        boolean createDenominationsTransaction = hasDenomOutput && (nonDenoms.size() == 1 || (nonDenoms.size() == 2 && hasCollateralOutput));
        boolean potentiallyPs = transactionDTO.getVout().size() == 1;

        if (createDenominationsTransaction) {
            return Transaction.PRIVATE_SEND_CREATE_DENOMINATIONS;
        } else if (potentiallyMixing) {
            return Transaction.PRIVATE_SEND_POTENTIALLY_MIXING;
        } else if (potentiallyPs) {
            return Transaction.PRIVATE_SEND_POTENTIALLY_PRIVATE_SEND;
        } else {
            return Transaction.PRIVATE_SEND_NONE;
        }
    }

    private Map<String, Object> createParams2(BlockDTO blockDTO) throws IOException {

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> block = new HashMap<>();
        Map<String, Object> blockProperties = new HashMap<>();
        blockProperties.put("bits", blockDTO.getBits());
        blockProperties.put("chainwork", blockDTO.getChainwork());
        blockProperties.put("difficulty", blockDTO.getDifficulty());
        blockProperties.put("hash", blockDTO.getHash());
        blockProperties.put("height", blockDTO.getHeight());
        blockProperties.put("mediantime", blockDTO.getMediantime());
        blockProperties.put("merkleroot", blockDTO.getMerkleroot());
        blockProperties.put("nonce", blockDTO.getNonce());
        blockProperties.put("size", blockDTO.getSize());
        blockProperties.put("time", blockDTO.getTime());
        blockProperties.put("version", blockDTO.getVersion());
        block.put("properties", blockProperties);
        result.put("block", block);
        boolean coinbaseTransaction = true;
        if (blockDTO.getHeight() > 0) {//Ignore genesis transaction since the outputs can't be spent (and the json api of dash core client doesn't work for the genesis transaction)
            List<TransactionDTO> txs = blockDTO.getTx().parallelStream().map(txid -> {
                try {
                    return client.getTrasactionByTxId(txid);
                } catch (IOException ex) {
                    throw new RuntimeException();
                }
            }).collect(Collectors.toList());

            block.put("coinbaseTransaction", coinbaseTxToParams2(txs.get(0)));

            List<Map<String, Object>> transactions = IntStream.range(1, txs.size()).mapToObj(i -> regularTxToParams2(txs.get(i), i)).collect(Collectors.toList());

            //List<Map<String, Object>> transactions = txs.subList(1, txs.size()).parallelStream().map(tx -> regularTxToParams2(tx)).collect(Collectors.toList());
            block.put("transactions", transactions);
        }
        //System.out.println("regular transactions: "+ txs.subList(1, txs.size()).size());

        //System.out.println("params="+result);
        return result;
    }

    private String createQuery2(boolean firstBlock) {
        if (firstBlock) {
            return "CREATE (newBlock:Block) "
                    + "SET newBlock = $block.properties ";
        }

        String blockQuery = "MATCH (previousBlock:Block {height:($block.properties.height-1)}) "
                + "CREATE (newBlock:Block)-[:PREVIOUS]->(previousBlock) "
                + "SET newBlock = $block.properties "
                + "WITH newBlock";

        String coinbaseTxQuery = "CREATE (coinbaseInput:TransactionInput)-[:INPUT]->(coinbaseTransaction:Transaction)-[:INCLUDED_IN]->(newBlock) "
                + "SET coinbaseTransaction = $block.coinbaseTransaction.properties "
                + "SET coinbaseInput = head($block.coinbaseTransaction.inputs).properties "
                + "WITH newBlock, coinbaseTransaction";

        String coinbaseOutputs = "FOREACH(output IN $block.coinbaseTransaction.outputs | "
                + "CREATE (coinbaseTransaction)-[:OUTPUT]->(out:TransactionOutput) "
                + "SET out=output.properties "
                + "FOREACH (addrStr IN output.scriptPubKey.addresses | "
                + "MERGE (address:Address {address:addrStr}) "
                + "CREATE (out)-[:ADDRESS]->(address) "
                + ")) "
                + "WITH newBlock";

        String txQuery = "UNWIND $block.transactions AS transaction "
                + "CREATE (tx:Transaction)-[:INCLUDED_IN]->(newBlock) "
                + "SET tx = transaction.properties "
                + "WITH tx, transaction";

        String outputsQuery = "FOREACH(output IN transaction.outputs | "
                + "CREATE (tx)-[:OUTPUT]->(out:TransactionOutput) "
                + "SET out=output.properties "
                + "FOREACH (addrStr IN output.scriptPubKey.addresses | "
                + "MERGE (address:Address {address:addrStr}) "
                + "CREATE (out)-[:ADDRESS]->(address) "
                + ")) "
                + "WITH tx, transaction";

//        String coinbaseQuery = "CREATE (tx)<-[:INPUT]-(coinbase:TransactionInput) "
//                + "SET coinbase=transaction.coinbase.properties WITH tx, transaction";
        String inputsQuery = "UNWIND transaction.inputs AS input "
                + "MATCH (t:Transaction {txid:input.txid})-[:OUTPUT]->(utxo:TransactionOutput {n:input.vout}) "
                //+ "USING INDEX t:Transaction(txid) "
                + "CREATE (tx)<-[:INPUT]-(in:TransactionInput)<-[:SPENT_IN]-(utxo) "
                + "SET in=input.properties "
                + "WITH tx, sum(utxo.valueSat)-transaction.totalOutputSat as feesSat "
                + "SET tx += { feesSat: feesSat }";

        String query = blockQuery + " " + coinbaseTxQuery + " " + coinbaseOutputs + " " + txQuery + " " + outputsQuery + " " + inputsQuery;
//System.out.println("QUERY="+query);
        return query;
    }

    @Transactional
    private boolean fillPageOfMixingPsTypes() {
        Pageable pageable = new PageRequest(0, 70);
        List<Transaction> txList = transcationRepository.findByPstype(Transaction.PRIVATE_SEND_POTENTIALLY_MIXING, 2, pageable);
        System.out.println(txList.size());
        for (Transaction tx : txList) {
            tx.setPstype(TransactionUtil.getPsType(tx));
        }
        transcationRepository.save(txList, 0);
        return !txList.isEmpty();
    }

    @Transactional
    private boolean fillPageOfPrivateSendPsTypes() {
        Pageable pageable = new PageRequest(0, 70);
        List<Transaction> txList = transcationRepository.findByPstype(Transaction.PRIVATE_SEND_POTENTIALLY_PRIVATE_SEND, 2, pageable);
        System.out.println(txList.size());
        for (Transaction tx : txList) {
            tx.setPstype(TransactionUtil.getPsType(tx));
        }
        transcationRepository.save(txList, 0);
        return !txList.isEmpty();
    }

    /**
     * Don't use this method for coinbase transactions
     *
     * @param transactionDTO
     * @param index Transaction index withing the containing block. Coinbase
     * transaction is 0.
     * @return
     */
    private Map<String, Object> regularTxToParams2(TransactionDTO transactionDTO, int index) {

        long psType = computeTentativePstype(transactionDTO);

        Map<String, Object> transaction = new HashMap<>();
        Map<String, Object> transactionProperties = new HashMap<>();
        ArrayList<Object> transactionInputs = new ArrayList<>();
        ArrayList<Object> transactionOutputs = new ArrayList<>();
        transaction.put("properties", transactionProperties);
        transaction.put("outputs", transactionOutputs);
        transaction.put("inputs", transactionInputs);
        transactionProperties.put("n", index);
        transactionProperties.put("locktime", transactionDTO.getLocktime());
        transactionProperties.put("pstype", psType);
        transactionProperties.put("size", transactionDTO.getSize());
        transactionProperties.put("txid", transactionDTO.getTxid());
        transactionProperties.put("version", transactionDTO.getVersion());
        long totalOutputSat = 0;
        for (TransactionDTO.VOut vout : transactionDTO.getVout()) {
            Map<String, Object> transactionOutput = new HashMap<>();
            Map<String, Object> transactionOutputProperties = new HashMap<>();
            transactionOutput.put("properties", transactionOutputProperties);
            transactionOutputProperties.put("n", vout.getN());
            //transactionOutputProperties.put("value", vout.getValue());
            transactionOutputProperties.put("valueSat", vout.getValueSat());
            totalOutputSat += vout.getValueSat();

            Map<String, Object> scriptPubKey = new HashMap<>();
            transactionOutput.put("scriptPubKey", scriptPubKey);
            if (vout.getScriptPubKey() != null && vout.getScriptPubKey().getAddresses() != null) {
                scriptPubKey.put("addresses", vout.getScriptPubKey().getAddresses());
            } else {
                scriptPubKey.put("addresses", new String[0]);
            }

            transactionOutputs.add(transactionOutput);
        }
        transaction.put("totalOutputSat", totalOutputSat);
        for (TransactionDTO.VIn vin : transactionDTO.getVin()) {
            Map<String, Object> transactionInput = new HashMap<>();
            Map<String, Object> transactionInputProperties = new HashMap<>();
            transactionInput.put("properties", transactionInputProperties);
            transactionInputProperties.put("sequence", vin.getSequence());
            transactionInput.put("txid", vin.getTxid());
            transactionInput.put("vout", vin.getVout());
            transactionInputs.add(transactionInput);
        }
        transaction.put("outputs", transactionOutputs);
        transaction.put("inputs", transactionInputs);
        return transaction;
    }

}
