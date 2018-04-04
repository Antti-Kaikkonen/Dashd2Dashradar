package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import java.io.IOException;

public interface BlockImportService {

    public void processBlock(BlockDTO block) throws IOException;

    public void fillPstypes();

    void createPreviousPSConnections();
    
    void createPreviousPSConnections(long afterBlock);

    void calculateTransactionFees();

    void last_block_of_day();

}
