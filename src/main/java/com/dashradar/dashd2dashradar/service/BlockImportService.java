package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import java.io.IOException;

public interface BlockImportService {

    public void processBlock(BlockDTO block) throws IOException;

    public void fillPstypes();

    void createPreviousPSConnections();

    void calculateTransactionFees();

    void create_BlockChainTotals();

    void input_count();

    void last_block_of_day();

    void mixing100_0_count();

    void mixing_0_01_count();

    void mixing_0_1_count();

    void mixing_10_0_count();

    void mixing_1_0_count();

    void output_count();

    void privatesend_tx_count();

    void total_block_rewards();

    void total_block_size();

    void total_output_volume();

    void total_transaction_size();

    void total_fees();

    void tx_count();

}
