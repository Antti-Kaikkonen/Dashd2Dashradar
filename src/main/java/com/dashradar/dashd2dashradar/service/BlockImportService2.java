package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import com.dashradar.dashradarbackend.domain.Block;
import java.io.IOException;

public interface BlockImportService2 {
    
    public void processBlock(BlockDTO block, boolean dayChanged) throws IOException;
    
    public void processTx(TransactionDTO transaction);
    
}
