/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */



package com.huawei.unibi.molap.surrogatekeysgenerator.dbbased;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.database.ConnectionPoolUtil;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.map.DatabaseConnectionMap;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import com.huawei.iweb.platform.logging.LogService;
import com.huawei.iweb.platform.logging.LogServiceFactory;
import com.huawei.unibi.molap.constants.MolapCommonConstants;
import com.huawei.unibi.molap.file.manager.composite.FileData;
import com.huawei.unibi.molap.file.manager.composite.IFileManagerComposite;
import com.huawei.unibi.molap.keygenerator.KeyGenerator;
import com.huawei.unibi.molap.keygenerator.factory.KeyGeneratorFactory;
import com.huawei.unibi.molap.metadata.SliceMetaData;
//import mondrian.olap.LevelType;
import com.huawei.unibi.molap.olap.LevelType;
import com.huawei.unibi.molap.schema.metadata.HierarchiesInfo;
import com.huawei.unibi.molap.schema.metadata.MolapInfo;
import com.huawei.unibi.molap.util.MolapDataProcessorLogEvent;
import com.huawei.unibi.molap.util.MolapDataProcessorUtil;
import com.huawei.unibi.molap.util.MolapProperties;
import com.huawei.unibi.molap.util.MolapUtil;


public class MolapSeqGenStep extends BaseStep implements StepInterface
{
    /**
     * NUM_CORES_DEFAULT_VAL
     */
    private static final int NUM_CORES_DEFAULT_VAL=2;

    /**
     * MolapSeqGenData
     */
    private MolapSeqGenData data;

    /**
     * MolapSeqGenStepMeta1
     */
    private MolapSeqGenStepMeta meta;

    /**
     * ReentrantLock getRowLock
     */
    private final Object getRowLock = new Object();

    /**
     * ReentrantLock putRowLock
     */
    private final Object putRowLock = new Object();

    /**
     * ROW_COUNT_INFO
     */
    private static final String ROW_COUNT_INFO = "rowcount";

    /**
     * Map of Connection
     */
    private Map<String, Connection> cons = new HashMap<String, Connection>(MolapCommonConstants.DEFAULT_COLLECTION_SIZE);
    
    private boolean isTerminated;
    
    /**
     * Normalized Hier and HierWriter map
     */
    private Map<String, HierarchyValueWriter> nrmlizedHierWriterMap = new HashMap<String, HierarchyValueWriter>(MolapCommonConstants.DEFAULT_COLLECTION_SIZE);

    /**
     * File manager
     */
    private IFileManagerComposite filemanager;
    
    /**
     * outSize
     */
    private int outSize;
    
    /**
     * row count
     */
    private int rowCount;

    /**
     * 
     * Comment for <code>LOGGER</code>
     * 
     */
    private static final LogService LOGGER = LogServiceFactory.getLogService(MolapSeqGenStep.class.getName());
    
    /**
     * readCounter
     */
    private long readCounter;
    
    /**
     * writeCounter
     */
    private long writeCounter;
    
    /**
     * logCounter
     */
    private int logCounter;

    /**
     * Constructor
     * 
     * @param s
     * @param stepDataInterface
     * @param c
     * @param t
     * @param dis
     */
    public MolapSeqGenStep(StepMeta s, StepDataInterface stepDataInterface,
            int c, TransMeta t, Trans dis)
    {
        super(s, stepDataInterface, c, t, dis);
    }

    /**
     * BYTE ENCODING
     */
    public static final String BYTE_ENCODING = "ISO-8859-1";

    /**
     * processRow
     */
    public boolean processRow(StepMetaInterface smi, StepDataInterface sdi)
            throws KettleException
    {
    	try{
        meta = (MolapSeqGenStepMeta)smi;
        
        data = (MolapSeqGenData)sdi;

        Object[] r = getRow();  // get row, blocks when needed!
        // no more input to be expected...
        if(r == null)
        {
            LOGGER.info(MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG, "Record Procerssed For table: "+ meta.getTableName());
            String logMessage= "Summary: Molap DB Based Seq Gen Step: Read: " + readCounter + ": Write: "+ writeCounter;
            LOGGER.info(MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG, logMessage);
            setOutputDone();
            return false;
        }
        if(first)
        {
            first = false;
            meta.initialize();

            meta.updateHierMappings(getInputRowMeta());
            
            data.setInputSize(getInputRowMeta().size());

            logCounter = Integer.parseInt(MolapCommonConstants.DATA_LOAD_LOG_COUNTER_DEFAULT_COUNTER);
            if(!meta.isAggregate())
            {
                updateHierarchyKeyGenerators(data.getKeyGenerators(),
                        meta.hirches, meta.dimLens, meta.dimColNames);
            }

            data.setGenerator(KeyGeneratorFactory.getKeyGenerator(getUpdatedLens(meta.dimLens, meta.dimPresent)));
           
            data.setOutputRowMeta((RowMetaInterface)getInputRowMeta().clone());
            
            

            // Make info object with all the data required for surrogate key
            // generator
            MolapInfo molapInfo = new MolapInfo();
            molapInfo.setDims(meta.dims);
            molapInfo.setDimColNames(meta.dimColNames);
            molapInfo.setKeyGenerators(data.getKeyGenerators());
            molapInfo.setHierTables(meta.hirches.keySet());
            molapInfo.setBatchSize(meta.getBatchSize());
            molapInfo.setAggregateLoad(meta.isAggregate());
            molapInfo.setStoreType(meta.getStoreType());
            molapInfo.setMaxKeys(meta.dimLens);
            molapInfo.setPropColumns(meta.getPropertiesColumns());
            molapInfo.setPropIndx(meta.getPropertiesIndices());
            molapInfo.setTimDimIndex(meta.timeDimeIndex);
            molapInfo.setTimeOrdinalCols(meta.timeOrdinalCols);
            molapInfo.setPropTypes(meta.getPropTypes());
            molapInfo.setBaseStoreLocation(updateStoreLocationAndPopulateMolapInfo(meta.getStoreLocation()));
            molapInfo.setTableName(meta.getTableName());
            molapInfo.setDimsPresent(meta.dimPresent);
            
            if(meta.timeIndex != -1)
            {
                molapInfo.setTimDimIndexEnd(molapInfo.getTimDimIndex()
                        + meta.timeLevels.length);
            }

            molapInfo.setTimeOrdinalIndices(meta.timeOrdinalIndices);
                
           data.setSurrogateKeyGen(new FileStoreSurrogateKeyGen(molapInfo, meta.getCurrentRestructNumber()));
                
            if(meta.timeIndex >= 0)
            {
                handleDimWithTime();
               
            }
            else
            {
                // We consider that there is no time dimension,in these case the
                handleDimWithoutTime();
                
            }
            List<HierarchiesInfo> metahierVoList = meta.getMetahierVoList();
            if(null != metahierVoList && !meta.isAggregate())
            {
                updateHierarichiesFromMetaDataFile(metahierVoList);
                // write the cache file in the disk
                writeRowCountFile(meta.getRowCountMap());
            }
            
        }
        // proecess the first
        readCounter++;
        Object[] out = process(r);
        if(out.length>0)
        {
            rowCount++;
            writeCounter++;
            putRow(data.getOutputRowMeta(), out);
        }
       
        // start multi-thread to process
        int numberOfNodes;
        try
        {
            numberOfNodes = Integer.parseInt(MolapProperties.getInstance()
                    .getProperty(MolapCommonConstants.NUM_CORES,
                            MolapCommonConstants.NUM_CORES_DEFAULT_VAL));
        }
        catch(NumberFormatException exc)
        {
            numberOfNodes=NUM_CORES_DEFAULT_VAL;
        }
        startProcess(numberOfNodes);
        
        if(rowCount==0)
        {
            putRow(data.getOutputRowMeta(), new Object[outSize]);
        }
        data.getSurrogateKeyGen().writeHeirDataToFileAndCloseStreams();
        updateAndWriteSliceMetadataFile();
        if(!meta.isAggregate())
        {
            closeNormalizedHierFiles();
        }
        setOutputDone();
        LOGGER.info(MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG, "Record Procerssed For table: "+ meta.getTableName());
        String logMessage= "Summary: Molap DB Based Seq Gen Step: Read: " + readCounter + ": Write: "+ writeCounter;
        LOGGER.info(MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG, logMessage);
    }catch(Exception ex)
    {
    	throw new RuntimeException(ex);
    }
        return false; 
    }
    

	/**
	 * Handle when time dimension is present
	 */
    private void handleDimWithTime() 
	{
		ValueMetaInterface[] metaInterface = new ValueMetaInterface[data.getOutputRowMeta()
		        .size() + meta.timeLevels.length - 1 + 1];

		for(int i = 0;i < meta.timeIndex;i++)
		{
		    metaInterface[i] = data.getOutputRowMeta().getValueMeta(i);

		}
		setValueInterface(metaInterface,
		        data.getOutputRowMeta().getValueMeta(meta.timeIndex));

		for(int i = meta.timeIndex + 1;i < data.getOutputRowMeta().size();i++)
		{
		    metaInterface[i + meta.timeLevels.length - 1] = data.getOutputRowMeta()
		            .getValueMeta(i);
		}

		int dimSize = 0;
        if(meta.isAggregate())
        {
          dimSize = meta.dims.length;  
        }
        else
        {
            dimSize = meta.normLength;
        }
		ValueMetaInterface[] out = new ValueMetaInterface[dimSize
		        + meta.msrs.length];
		for(int i = 0;i < dimSize;i++)
		{//CHECKSTYLE:OFF
		    out[i] = metaInterface[meta.dims[i]];
		}//CHECKSTYLE:ON
		int l = 0;
		int len = dimSize + meta.msrs.length;
		for(int i = dimSize;i < len;i++)
		{//CHECKSTYLE:OFF
		    out[i] = metaInterface[meta.msrs[l]];
		    l++;
		}//CHECKSTYLE:ON
		data.getOutputRowMeta().setValueMetaList(Arrays.asList(out));
	}

	/**
	 * We consider that there is no time dimension,in these case the
     * timeIndex = -1
	 */
    private void handleDimWithoutTime() 
    {
        int dimSize = 0;
        if(meta.isAggregate())
        {
          dimSize = meta.dims.length;  
        }
        else
        {
            dimSize = meta.normLength;
        }
        
        ValueMetaInterface[] out = new ValueMetaInterface[dimSize
                + meta.msrs.length];
        outSize= out.length;
        for(int i = 0;i < dimSize;i++)
        {
            String name = data.getOutputRowMeta().getValueMeta(i).getName();
            ValueMetaInterface x = new ValueMeta(name,
                    ValueMetaInterface.TYPE_STRING,
                    ValueMetaInterface.STORAGE_TYPE_BINARY_STRING);
            x.setStorageMetadata((new ValueMeta(name,
                    ValueMetaInterface.TYPE_STRING,
                    ValueMetaInterface.STORAGE_TYPE_NORMAL)));
            x.setStringEncoding(BYTE_ENCODING);
            x.setStringEncoding(BYTE_ENCODING);
            x.getStorageMetadata().setStringEncoding(BYTE_ENCODING);

            out[i] = x;
        }
        int l = 0;
        int len = dimSize + meta.msrs.length;
        for(int i = dimSize;i < len;i++)
        {
            out[i] = data.getOutputRowMeta().getValueMeta(l + dimSize);
            l++;
        }
        data.getOutputRowMeta().setValueMetaList(Arrays.asList(out));
        
    }

	private void startProcess(int numberOfNodes) throws KettleException {
		ExecutorService exec = Executors.newFixedThreadPool(numberOfNodes);
        
        Callable<Void> callable = new Callable<Void>()
        {
            @Override
            public Void call() throws Exception
            {
                try
                {
                    doProcess();
                }
                catch(KettleException e)
                {
                	isTerminated = true;
                    throw e;
                }
                return null;
            }
        };
        List<Future<Void>> results = new ArrayList<Future<Void>>(MolapCommonConstants.CONSTANT_SIZE_TEN);
        for(int i = 0;i < numberOfNodes;i++)
        {
            results.add(exec.submit(callable));
        }

        Future[] resultArray = results.toArray(new Future[results.size()]);
        boolean complete = false;
        try
        {//CHECKSTYLE:OFF
            while (!complete)
            {//CHECKSTYLE:ON
                complete = true;
                for (int i = 0; i < resultArray.length; i++)
                {
                    if (!resultArray[i].isDone())
                    {
                        complete = false;

                    }

                }
                if (isTerminated)
                {
                    exec.shutdownNow();
                    throw new KettleException(
                            "Interrupted due to failing of other threads");
                }
                Thread.sleep(5);

            }
        }
        catch (InterruptedException e) 
        {
            throw new KettleException("Thread InterruptedException", e);
        }
        exec.shutdown();
	}

    /**
     * updateAndWriteSliceMetadataFile
     * 
     * @throws KettleException
     */
    private void updateAndWriteSliceMetadataFile() throws KettleException
    {
        String storeLocation = updateStoreLocationAndPopulateMolapInfo(meta
                .getStoreLocation());
        //
        
        int restructFolderNumber = meta.getCurrentRestructNumber();
        
        String sliceMetaDataFilePath = storeLocation + File.separator
                + MolapCommonConstants.RESTRUCTRE_FOLDER + restructFolderNumber
                + File.separator + meta.getTableName() + File.separator
                + MolapUtil.getSliceMetaDataFileName(restructFolderNumber);

        File file = new File(sliceMetaDataFilePath);
        if(file.exists())
        {
            return;
        }
        SliceMetaData sliceMetaData = new SliceMetaData();
        //
        sliceMetaData.setDimensions(meta.dimColNames);
        sliceMetaData.setMeasures(meta.measureNames);
        sliceMetaData.setDimLens(getUpdatedLens(meta.dimLens, meta.dimPresent));
        sliceMetaData.setMeasuresAggregator(meta.msrAggregators);
        sliceMetaData.setHeirAnKeySize(meta.getHeirKeySize());
        int measureOrdinal =0;
        for(String agg : meta.msrAggregators)
        {
            if("count".equals(agg))
            {
                break;
            }
            measureOrdinal++;
        }
        sliceMetaData.setKeyGenerator(KeyGeneratorFactory.getKeyGenerator(getUpdatedLens(meta.dimLens, meta.dimPresent)));
        MolapDataProcessorUtil.writeFileAsObjectStream(sliceMetaDataFilePath, sliceMetaData);
    }

    private void writeRowCountFile(Map<String, Integer> rowCountMap) throws KettleException
    {
        FileOutputStream fileOutputStream = null;
        FileChannel fileChannel = null;
        //
        String storeLocation = MolapUtil.getCarbonStorePath(null, null);

        storeLocation = storeLocation + File.separator
                + meta.getStoreLocation();

        int restructFolderNumber = meta.getCurrentRestructNumber();

        storeLocation = storeLocation + File.separator
                + MolapCommonConstants.RESTRUCTRE_FOLDER + restructFolderNumber
                + File.separator + ROW_COUNT_INFO;
        
        File rowCountFile = new File(storeLocation);
        //
        boolean isFileCreated = false;
        if(!rowCountFile.exists())
        {
            try
            {
            	isFileCreated = rowCountFile.createNewFile();
            }
            catch(IOException e)
            {
                throw new KettleException("Unable to create rowCounter file", e);
            }
            if(!isFileCreated)
            {
            	throw new KettleException("Unable to create rowCounter file");
            }
        }

        try
        {
            fileOutputStream = new FileOutputStream(rowCountFile);
        }
        catch(FileNotFoundException e)
        {
            throw new KettleException("row count File not found", e);
        }
        fileChannel = fileOutputStream.getChannel();
        try
        {
            for(Entry<String, Integer> entry : rowCountMap.entrySet())
            {
                // total length
                int infoLength = 0;
                String tableName = entry.getKey();
                byte[] tableNameBytes = tableName.getBytes(Charset.defaultCharset());
                // first 4 bytes table name length next is the table name and
                // last 4
                // bytes row count.
                infoLength = 4 + tableNameBytes.length + 4;

                ByteBuffer byteBuffer = ByteBuffer.allocate(infoLength + 4);

                byteBuffer.putInt(infoLength);
                byteBuffer.putInt(tableNameBytes.length);
                byteBuffer.put(tableNameBytes);
                byteBuffer.putInt(entry.getValue());
                byteBuffer.flip();
                fileChannel.write(byteBuffer);
                byteBuffer.clear();
            }
        }
        catch(IOException e)
        {
            throw new KettleException("Unable to write row count file", e);
        }
        finally
        {
            MolapUtil.closeStreams(fileChannel, fileOutputStream);
        }


    }

    private void doProcess() throws KettleException
    {
        while(true)
        {
            Object[] r = null;
            synchronized(getRowLock)
            {
                if(readCounter % logCounter == 0)
                {
                    LOGGER.info(
                            MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG,
                            "Record Procerssed For table: "
                                    + meta.getTableName());
                    String logMessage = "Molap Csv Based Seq Gen Step: Record Read: "
                            + readCounter;
                    LOGGER.info(
                            MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG,
                            logMessage);
                }
                r = getRow();
                readCounter++;
            }
            // no more input to be expected...
            if(r == null)
            {
                readCounter--;
                break;
            }

            Object[] out = process(r);
            synchronized(putRowLock)
            {
                if(out.length>0)
                {
                    rowCount++;
                    writeCounter++;
                    putRow(data.getOutputRowMeta(), out);
                }
            }
            // Some basic logging
            if(checkFeedback(getLinesRead()))
            {
                if(log.isBasic())
                {
                    logBasic("Linenr " + getLinesRead());
                }
            }
        }
    }

    private String updateStoreLocationAndPopulateMolapInfo(String schemaCubeName)
    {
        //
        String storeLocation = MolapUtil.getCarbonStorePath(null,  null);
        File f = new File(storeLocation);
        String absoluteStorePath = f.getAbsolutePath();
        //
        if(absoluteStorePath.length() > 0
                && absoluteStorePath.charAt(absoluteStorePath.length() - 1) == '/')
        {
            absoluteStorePath = absoluteStorePath + schemaCubeName;
        }
        else
        {
            absoluteStorePath = absoluteStorePath
                    + System.getProperty("file.separator") + schemaCubeName;
        }
        return absoluteStorePath;
    }

    private Object[] process(Object[] r) throws KettleException
    {
        List<Integer> timeOrdinalColValues = null;
        if(meta.timeIndex >= 0)
        {
        	timeOrdinalColValues = new ArrayList<Integer>(MolapCommonConstants.CONSTANT_SIZE_TEN);
            Object[] outputRow = new Object[r.length + meta.timeLevels.length
                    - 1];
            for(int i = 0;i < meta.timeIndex;i++)
            {//CHECKSTYLE:OFF
                outputRow[i] = r[i];
            }//CHECKSTYLE:ON

            try
            {
                getTimeValue(outputRow,
                        data.getOutputRowMeta().getValueMeta(meta.timeDimeIndex)
                                .getString((byte[])r[meta.timeIndex]),
                        timeOrdinalColValues, meta.timeIndex);
            }
            catch(Exception e)
            {
            	throw new KettleException(e.getMessage(), e);
            }

            for(int i = meta.timeIndex + 1;i < r.length;i++)
            {//CHECKSTYLE:OFF
                outputRow[i + meta.timeLevels.length - 1] = r[i];
            }//CHECKSTYLE:ON
            r = outputRow;
        }

        int timeSplitAddition = meta.timeIndex >= 0 ? (meta.timeLevels.length - 1)
                : 0;

        // Convert all the data to string other than measures. Here measures
        // expected to come after all dimensions.
        // TODO what if measure is derived from intermediate field.
        int k=0;
        for(int i = 0;i < meta.dims.length;i++)
        {
            int dimIndex= -1;
            if(meta.dimPresent[i])
            {
                dimIndex = k;
                k++;
            }
            else
            {
                continue;
            }
            
            if(dimIndex < meta.timeIndex)
            {
                r[dimIndex] = getInputRowMeta().getValueMeta(dimIndex).getString(r[dimIndex]);
            }
            else if(dimIndex > meta.timeIndex + timeSplitAddition)
            {
                r[dimIndex] = getInputRowMeta().getValueMeta(dimIndex - timeSplitAddition)
                        .getString(r[dimIndex]);
            }
        }

        // Copy the dimension String values to output
        //For Aggregate table dimension will always be denormalized. so in that case we need to take the length 
        // of the dimensions.
        int dimensionLength = 0;
        if(meta.isAggregate())
        {
            dimensionLength = meta.dims.length;
        }
        else
        {
            dimensionLength = meta.normLength;
        }
        Object[] out = new Object[dimensionLength + meta.msrs.length + 2];
        for(int i = 0;i < dimensionLength;i++)
        {
            out[i] = r[i].toString();
        }

        // Copy the measure byte[] values to output
        int l = 0;
        int len = dimensionLength + meta.msrs.length;
        for(int i = dimensionLength;i < len;i++)
        {//CHECKSTYLE:OFF
            out[i] = r[l+ dimensionLength];
            l++;
        }//CHECKSTYLE:ON

        // Convert all the dimension string to surrogate keys
        Object[] generateSurrogateKeys = data.getSurrogateKeyGen()
                .generateSurrogateKeys(r, out, timeOrdinalColValues);

        // copy row to possible alternate rowset(s)
        return generateSurrogateKeys;
    }
    
    
    private void closeNormalizedHierFiles() throws KettleException
    {
        if(null == filemanager)
        {
            return;
        }
        int hierLen = filemanager.size();

        for(int i = 0;i < hierLen;i++)
        {
            FileData hierFileData = (FileData)filemanager.get(i);
            String hierInProgressFileName = hierFileData.getFileName();
            HierarchyValueWriter hierarchyValueWriter = nrmlizedHierWriterMap
                    .get(hierInProgressFileName);
            
            hierInProgressFileName = hierFileData.getFileName();
            String storePath = hierFileData.getStorePath();
            String changedFileName = hierInProgressFileName.substring(0,
                    hierInProgressFileName.lastIndexOf('.'));
            String hierName = changedFileName.substring(0,
                    changedFileName.lastIndexOf('.'));
            
            List<byte[]> byteArrayList = hierarchyValueWriter.getByteArrayList();
            Collections.sort(byteArrayList,  data.getKeyGenerators().get(hierName));
            byte[] bytesTowrite=null;
            for(byte[] bytes : byteArrayList)
            {
                bytesTowrite = new byte[bytes.length + 4];
                System.arraycopy(bytes, 0, bytesTowrite, 0, bytes.length);
                hierarchyValueWriter.writeIntoHierarchyFile(bytesTowrite);
            }



            // now write the byte array in the file.
            BufferedOutputStream bufferedOutStream = hierarchyValueWriter
                    .getBufferedOutStream();
            if(null == bufferedOutStream)
            {
                continue;
            }
            MolapUtil.closeStreams(bufferedOutStream);

            hierInProgressFileName = hierFileData.getFileName();
            File currentFile = new File(storePath + File.separator
                    + hierInProgressFileName);
            File destFile = new File(storePath + File.separator
                    + changedFileName);

            if(!currentFile.renameTo(destFile))
            {
                LOGGER.error(MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG, "Problem while renaming the file");
            }
        }

    }
    
    private int[] getUpdatedLens(int[] lens,boolean[] presentDims)
    {
        int k=0;
        int[] integers = new int[meta.normLength];
        for(int i = 0;i < lens.length;i++)
        {
            if(presentDims[i])
            {//CHECKSTYLE:OFF
                integers[k] = lens[i];
                k++;
            }//CHECKSTYLE:ON
        }
        return integers;
    }

    private void updateHierarichiesFromMetaDataFile(List<HierarchiesInfo> metahierVoList)
            throws KettleException
    {
        try
        {
            for(int i = 0;i < metahierVoList.size();i++)
            {
                HierarchiesInfo hierarichiesVO = metahierVoList.get(i);
                String query = hierarichiesVO.getQuery();
                if(null == query) // table will be denormalized so no foreign key , primary key for this hierarchy 
                {                  // Direct column names will be present in the csv file. in that case continue.  
                    continue;
                }
            }
        }
        catch(Exception e)
        {
            throw new KettleException(e.getMessage(), e);
        }
        try
        {
            for(Entry<String, Connection> entry : cons.entrySet())
            {
                entry.getValue().close();
            }
        }
        catch(Exception e)
        {
            throw new KettleException(e.getMessage(), e);
        }
    }

    public synchronized void connect(String group , String partitionId,Database database)
            throws Exception
    {
        // Before anything else, let's see if we already have a connection
        // defined for this group/partition!
        // The group is called after the thread-name of the transformation or
        // job that is running
        // The name of that threadname is expected to be unique (it is in
        // Kettle)
        // So the deal is that if there is another thread using that, we go for
        // it.
        //
        Connection connection = null;
        if (!Const.isEmpty(group))
        {

            DatabaseConnectionMap map = DatabaseConnectionMap.getInstance();

            // Try to find the connection for the group
            Database lookup = map.getDatabase(group , partitionId , database);
            if (lookup == null) // We already opened this connection for the
                                // partition & database in this group
            {
                // Do a normal connect and then store this database object for
                // later re-use.
                connection = ConnectionPoolUtil.getConnection(log ,
                        database.getDatabaseMeta() , partitionId);

                map.storeDatabase(group , partitionId , database);
            }
            else
            {
                connection = lookup.getConnection();
                lookup.setOpened(lookup.getOpened() + 1); // if this counter
                                                          // hits 0 again, close
                                                          // the connection.
            }
        }
        else
        {
            // Proceed with a normal connect
            connection = ConnectionPoolUtil.getConnection(log ,
                    database.getDatabaseMeta() , partitionId);
        }
        database.setConnection(connection);
    }

    /**
     * Read all the data values in String format. Identify any Ordinal column is
     * defined and fill the ordinal integer value in the given list
     * 
     * @param t
     * @param val
     * @param timOrdinalColValues
     * @throws Exception
     * 
     */
    private void getTimeValue(Object[] t, String val,
            List<Integer> timOrdinalColValues, int startIndex) throws Exception
    {
        //
        Date d = null;
        SimpleDateFormat monthFormat = new SimpleDateFormat("MMM");
        try
        {
            d = meta.timeFormat.parse(val);
        }
        catch(java.text.ParseException e)
        {
            // e.printStackTrace();
            return;
        }
        for(int i = 0;i < meta.timeLevels.length;i++)
        {
            int index = startIndex + i;
            if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeYears.name()))
            {
                int year = d.getYear() + 1900;
                t[index] = String.valueOf(year)/* .getBytes() */;
                //
                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(year);
                }
            }
            else if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeQuarters
                    .name()))
            {
                int quarterID = (d.getMonth() / 3 + 1);
                t[index] = ("Qtr" + quarterID)/* .getBytes() */;
                //
                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(quarterID);
                }
            }
            else if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeMonths
                    .name()))
            {
                //
                t[index] = monthFormat.format(d)/* .getBytes() */;

                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(d.getMonth() + 1);
                }
            }
            else if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeDays
                    .name()))
            {
                t[index] = Long.toString(d.getDate())/* .getBytes() */;
                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(d.getDate());
                }
            }
            else if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeHours
                    .name()))
            {
                //
                t[index] = Long.toString((d.getHours() + 1))/* .getBytes() */;
                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(d.getHours() + 1);
                }
            }
            else if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeMinutes
                    .name()))
            {
                t[index] = Long.toString(d.getMinutes())/* .getBytes() */;
                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(d.getMinutes());
                }
            }
            else if(meta.timeLevels[i].equalsIgnoreCase(LevelType.TimeSeconds
                    .name()))
            {
                //
                t[index] = Long.toString(d.getSeconds())/* .getBytes() */;
                if(meta.timeOrdinalCols[i] != null)
                {
                    timOrdinalColValues.add(d.getSeconds());
                }
            }
        }
    }

    private void setValueInterface(ValueMetaInterface[] t,
            ValueMetaInterface actual)
    {
        for(int i = 0;i < meta.timeLevels.length;i++)
        {
            ValueMetaInterface metaInterface = actual.clone();
            metaInterface.setName(meta.dimColNames[meta.timeDimeIndex + i]);
            t[meta.timeIndex + i] = metaInterface;
        }
    }

    /**
     * According to the hierarchies,generate the varLengthKeyGenerator
     * 
     * @param keyGenerators
     * @param hirches
     * @param dimLens
     * 
     */
    private void updateHierarchyKeyGenerators(
            Map<String, KeyGenerator> keyGenerators,
            Map<String, int[]> hirches, int[] dimLens, String[] dimCols)
    {
        //
        String timeHierName = "";
        if(meta.getMolapTime() == null || "".equals(meta.getMolapTime()))
        {
            timeHierName = "";
        }
        else
        {
            String[] hies = meta.getMolapTime().split(":");
            timeHierName = hies[1];
        }
        
        Iterator<Entry<String,int[]>> itr = hirches.entrySet().iterator();
        
        while(itr.hasNext())
        {
        	Entry<String,int[]> hieEntry = itr.next();
            String name = hieEntry.getKey();
            int[] a = hieEntry.getValue();
            int[] lens = new int[a.length];
            //
            if(name.equalsIgnoreCase(timeHierName))
            {
                for(int i = 0;i < a.length;i++)
                {//CHECKSTYLE:OFF
                    lens[i] = dimLens[a[i]];
                }//CHECKSTYLE:ON
            }
            else
            {
                for(int i = 0;i < a.length;i++)
                {
                    lens[i] = dimLens[a[i]];
                }
            }
            KeyGenerator generator = KeyGeneratorFactory.getKeyGenerator(lens);
            keyGenerators.put(name, generator);
        
        }
                
    }

    
    public boolean init(StepMetaInterface smi, StepDataInterface sdi)
    {
        meta = (MolapSeqGenStepMeta)smi;
        data = (MolapSeqGenData)sdi;
        return super.init(smi, sdi);
    }

    public void dispose(StepMetaInterface smi, StepDataInterface sdi)
    {
        meta = (MolapSeqGenStepMeta)smi;
        data = (MolapSeqGenData)sdi;

        MolapDimSurrogateKeyGen surrogateKeyGen = data.getSurrogateKeyGen();

        try
        {
            data.getSurrogateKeyGen().close();
            
        }
        catch(Exception e)
        {
            LOGGER.error(
                    MolapDataProcessorLogEvent.UNIBI_MOLAPDATAPROCESSOR_MSG, e);
        }
        surrogateKeyGen.hierCache = null;
        surrogateKeyGen.memberCache = null;
        super.dispose(smi, sdi);
    }
}
