package com.dashradar.dashd2dashradar.service;

import com.dashradar.dashdhttpconnector.dto.BlockDTO;
import com.dashradar.dashdhttpconnector.dto.TransactionDTO;
import java.io.IOException;

public interface BlockImportService {
    
    public void processBlock(BlockDTO block, boolean dayChanged) throws IOException;
    
    public void processTx(TransactionDTO transaction);
    
}
