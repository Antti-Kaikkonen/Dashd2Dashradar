package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import java.io.IOException;

public interface BlockImportService2 {
    
    public void processBlock(BlockDTO block) throws IOException;
    
    public void processTx(TransactionDTO transaction);
    
}
