/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.controlprogram.caching;

import java.io.IOException;
import java.lang.ref.SoftReference;

import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.api.DMLScript.RUNTIME_PLATFORM;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.lops.Lop;
import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.ParForProgramBlock.PDataPartitionFormat;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.instructions.spark.data.BroadcastObject;
import org.apache.sysml.runtime.instructions.spark.data.RDDObject;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.MatrixDimensionsMetaData;
import org.apache.sysml.runtime.matrix.MatrixFormatMetaData;
import org.apache.sysml.runtime.matrix.MetaData;
import org.apache.sysml.runtime.matrix.data.FileFormatProperties;
import org.apache.sysml.runtime.matrix.data.InputInfo;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.NumItemsByEachReducerMetaData;
import org.apache.sysml.runtime.matrix.data.OutputInfo;
import org.apache.sysml.runtime.util.DataConverter;
import org.apache.sysml.runtime.util.IndexRange;
import org.apache.sysml.runtime.util.MapReduceTool;


/**
 * Represents a matrix in control program. This class contains method to read
 * matrices from HDFS and convert them to a specific format/representation. It
 * is also able to write several formats/representation of matrices to HDFS.

 * IMPORTANT: Preserve one-to-one correspondence between {@link MatrixObject}
 * and {@link MatrixBlock} objects, for cache purposes.  Do not change a
 * {@link MatrixBlock} object without informing its {@link MatrixObject} object.
 * 
 */
public class MatrixObject extends CacheableData<MatrixBlock>
{
	private static final long serialVersionUID = 6374712373206495637L;

	/**
	 * Current state of pinned variables, required for guarded collect.
	 */
	private static ThreadLocal<Long> sizePinned = new ThreadLocal<Long>() {
        @Override protected Long initialValue() { return 0L; }
    };

	/**
	 * Object that holds the metadata associated with the matrix, which
	 * includes: 1) Matrix dimensions, if available 2) Number of non-zeros, if
	 * available 3) Block dimensions, if applicable 4) InputInfo -- subsequent
	 * operations that use this Matrix expect it to be in this format.
	 * 
	 * When the matrix is written to HDFS (local file system, as well?), one
	 * must get the OutputInfo that matches with InputInfo stored inside _mtd.
	 */
	private MetaData _metaData = null;
	
	//additional names and flags
	private boolean _requiresLocalWrite = false; //flag if local write for read obj
	private boolean _isAcquireFromEmpty = false; //flag if read from status empty 
	private boolean _updateInPlaceFlag = false; //flag if in-place update
	
	//spark-specific handles
	//note: we use the abstraction of LineageObjects for two reasons: (1) to keep track of cleanup
	//for lazily evaluated RDDs, and (2) as abstraction for environments that do not necessarily have spark libraries available
	private RDDObject _rddHandle = null; //RDD handle
	private BroadcastObject _bcHandle = null; //Broadcast handle
	
	/**
	 * Information relevant to partitioned matrices.
	 */
	private boolean _partitioned = false; //indicates if obj partitioned
	private PDataPartitionFormat _partitionFormat = null; //indicates how obj partitioned
	private int _partitionSize = -1; //indicates n for BLOCKWISE_N
	private String _partitionCacheName = null; //name of cache block
	private MatrixBlock _partitionInMemory = null;

	/**
	 * Constructor that takes only the HDFS filename.
	 */
	public MatrixObject (ValueType vt, String file)
	{
		this (vt, file, null); //HDFS file path
	}
	
	/**
	 * Constructor that takes both HDFS filename and associated metadata.
	 */
	public MatrixObject( ValueType vt, String file, MetaData mtd )
	{
		super (DataType.MATRIX, vt);
		_metaData = mtd; 
		_hdfsFileName = file;
		
		_cache = null;
		_data = null;
	}
	
	/**
	 * Copy constructor that copies meta data but NO data.
	 * 
	 * @param mo
	 */
	public MatrixObject( MatrixObject mo )
	{
		//base copy constructor
		super(mo);

		MatrixFormatMetaData metaOld = (MatrixFormatMetaData)mo.getMetaData();
		_metaData = new MatrixFormatMetaData(new MatrixCharacteristics(metaOld.getMatrixCharacteristics()),
				                             metaOld.getOutputInfo(), metaOld.getInputInfo());
		
		_updateInPlaceFlag = mo._updateInPlaceFlag;
		_partitioned = mo._partitioned;
		_partitionFormat = mo._partitionFormat;
		_partitionSize = mo._partitionSize;
		_partitionCacheName = mo._partitionCacheName;
	}
	
	@Override
	public void setMetaData(MetaData md) {
		_metaData = md;
	}
	
	@Override
	public MetaData getMetaData() {
		return _metaData;
	}

	@Override
	public void removeMetaData() {
		_metaData = null;
	}
	
	@Override
	public void updateMatrixCharacteristics (MatrixCharacteristics mc) {
		((MatrixDimensionsMetaData)_metaData).setMatrixCharacteristics( mc );
	}

	/**
	 * Make the matrix metadata consistent with the in-memory matrix data
	 * @throws CacheException 
	 */
	public void refreshMetaData() 
		throws CacheException
	{
		if ( _data == null || _metaData ==null ) //refresh only for existing data
			throw new CacheException("Cannot refresh meta data because there is no data or meta data. "); 
		    //we need to throw an exception, otherwise input/output format cannot be inferred
		
		MatrixCharacteristics mc = ((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics();
		mc.setDimension( _data.getNumRows(),
						 _data.getNumColumns() );
		mc.setNonZeros( _data.getNonZeros() );		
	}

	/**
	 * 
	 * @return
	 */
	public long getNumRows () 
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		MatrixCharacteristics mc = meta.getMatrixCharacteristics();
		return mc.getRows ();
	}

	/**
	 * 
	 * @return
	 */
	public long getNumColumns() 
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		MatrixCharacteristics mc = meta.getMatrixCharacteristics();
		return mc.getCols ();
	}
	
	/**
	 * 
	 * @return
	 */
	public long getNumRowsPerBlock() 
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		MatrixCharacteristics mc = meta.getMatrixCharacteristics();
		return mc.getRowsPerBlock();
	}
	
	/**
	 * 
	 * @return
	 */
	public long getNumColumnsPerBlock() 
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		MatrixCharacteristics mc = meta.getMatrixCharacteristics();
		return mc.getColsPerBlock();
	}
	
	/**
	 * 
	 * @return
	 */
	public long getNnz() 
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		MatrixCharacteristics mc = meta.getMatrixCharacteristics();
		return mc.getNonZeros();
	}
	
	/**
	 * 
	 * @return
	 */
	public double getSparsity() 
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		MatrixCharacteristics mc = meta.getMatrixCharacteristics();
		
		return ((double)mc.getNonZeros())/mc.getRows()/mc.getCols();
	}
	
	/**
	 * 
	 * @return
	 */
	public MatrixCharacteristics getMatrixCharacteristics()
	{
		MatrixDimensionsMetaData meta = (MatrixDimensionsMetaData) _metaData;
		return meta.getMatrixCharacteristics();
	}
	
	public String toString()
	{ 
		StringBuilder str = new StringBuilder();
		str.append("Matrix: ");
		str.append(_hdfsFileName + ", ");
		
		if ( _metaData instanceof NumItemsByEachReducerMetaData ) {
			str.append("NumItemsByEachReducerMetaData");
		} 
		else 
		{
			try
			{
				MatrixFormatMetaData md = (MatrixFormatMetaData)_metaData;
				if ( md != null ) {
					MatrixCharacteristics mc = ((MatrixDimensionsMetaData)_metaData).getMatrixCharacteristics();
					str.append(mc.toString());
					
					InputInfo ii = md.getInputInfo();
					if ( ii == null )
						str.append("null");
					else {
						str.append(", ");
						str.append(InputInfo.inputInfoToString(ii));
					}
				}
				else {
					str.append("null, null");
				}
			}
			catch(Exception ex)
			{
				LOG.error(ex);
			}
		}
		str.append(", ");
		str.append(isDirty() ? "dirty" : "not-dirty");
		
		return str.toString();
	}
	
	public RDDObject getRDDHandle()
	{
		return _rddHandle;
	}
	
	public void setRDDHandle( RDDObject rdd )
	{
		//cleanup potential old back reference
		if( _rddHandle != null )
			_rddHandle.setBackReference(null);
		
		//add new rdd handle
		_rddHandle = rdd;
		if( _rddHandle != null )
			rdd.setBackReference(this);
	}
	
	public BroadcastObject getBroadcastHandle()
	{
		return _bcHandle;
	}
	
	public void setBroadcastHandle( BroadcastObject bc )
	{
		//cleanup potential old back reference
		if( _bcHandle != null )
			_bcHandle.setBackReference(null);
			
		//add new broadcast handle
		_bcHandle = bc;
		if( _bcHandle != null )
			bc.setBackReference(this);
	}
	
	
	// *********************************************
	// ***                                       ***
	// ***    HIGH-LEVEL METHODS THAT SPECIFY    ***
	// ***   THE LOCKING AND CACHING INTERFACE   ***
	// ***                                       ***
	// *********************************************
	
	
	/**
	 * Acquires a shared "read-only" lock, produces the reference to the matrix data,
	 * restores the matrix to main memory, reads from HDFS if needed.
	 * 
	 * Synchronized because there might be parallel threads (parfor local) that
	 * access the same MatrixObjectNew object (in case it was created before the loop).
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED, READ;
	 * Out-Status: READ(+1).
	 * 
	 * @return the matrix data reference
	 * @throws CacheException 
	 */
	@Override
	public synchronized MatrixBlock acquireRead()
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Acquire read "+getVarName());
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		if ( !isAvailableToRead() )
			throw new CacheException ("MatrixObject not available to read.");
		
		//get object from cache
		if( _data == null )
			getCache();
		
		//read data from HDFS/RDD if required
		//(probe data for cache_nowrite / jvm_reuse)  
		if( isEmpty(true) && _data==null ) 
		{			
			try
			{
				if( DMLScript.STATISTICS )
					CacheStatistics.incrementHDFSHits();
				
				if( getRDDHandle()==null || getRDDHandle().allowsShortCircuitRead() )
				{
					//check filename
					if( _hdfsFileName == null )
						throw new CacheException("Cannot read matrix for empty filename.");

					//read matrix from hdfs
					_data = readMatrixFromHDFS( _hdfsFileName );
					
					//mark for initial local write despite read operation
					_requiresLocalWrite = CACHING_WRITE_CACHE_ON_READ;
				}
				else
				{
					//read matrix from rdd (incl execute pending rdd operations)
					MutableBoolean writeStatus = new MutableBoolean();
					_data = readMatrixFromRDD( getRDDHandle(), writeStatus );
					
					//mark for initial local write (prevent repeated execution of rdd operations)
					if( writeStatus.booleanValue() )
						_requiresLocalWrite = CACHING_WRITE_CACHE_ON_READ;
					else		
						_requiresLocalWrite = true;
				}
				
				setDirty(false);
			}
			catch (IOException e)
			{
				throw new CacheException("Reading of " + _hdfsFileName + " ("+getVarName()+") failed.", e);
			}
			
			_isAcquireFromEmpty = true;
		}
		else if( DMLScript.STATISTICS )
		{
			if( _data!=null )
				CacheStatistics.incrementMemHits();
		}
		
		//cache status maintenance
		super.acquire( false, _data==null );	
		updateStatusPinned(true);
		
		if( DMLScript.STATISTICS ){
			long t1 = System.nanoTime();
			CacheStatistics.incrementAcquireRTime(t1-t0);
		}
		
		return _data;
	}
	
	/**
	 * Acquires the exclusive "write" lock for a thread that wants to change matrix
	 * cell values.  Produces the reference to the matrix data, restores the matrix
	 * to main memory, reads from HDFS if needed.
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED;
	 * Out-Status: MODIFY.
	 * 
	 * @return the matrix data reference
	 * @throws CacheException 
	 */
	@Override
	public synchronized MatrixBlock acquireModify() 
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Acquire modify "+getVarName());
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		if ( !isAvailableToModify() )
			throw new CacheException("MatrixObject not available to modify.");
		
		//get object from cache
		if( _data == null )
			getCache();
		
		//read data from HDFS if required
		if( isEmpty(true) && _data == null )
		{
			//check filename
			if( _hdfsFileName == null )
				throw new CacheException("Cannot read matrix for empty filename.");
			
			//load data
			try
			{
				_data = readMatrixFromHDFS( _hdfsFileName );
			}
			catch (IOException e)
			{
				throw new CacheException("Reading of " + _hdfsFileName + " ("+getVarName()+") failed.", e);
			}
		}

		//cache status maintenance
		super.acquire( true, _data==null );
		updateStatusPinned(true);
		setDirty(true);
		_isAcquireFromEmpty = false;
		
		if( DMLScript.STATISTICS ){
			long t1 = System.nanoTime();
			CacheStatistics.incrementAcquireMTime(t1-t0);
		}
		
		return _data;
	}
	
	/**
	 * Acquires the exclusive "write" lock for a thread that wants to throw away the
	 * old matrix data and link up with new matrix data.  Abandons the old matrix data
	 * without reading it.  Sets the new matrix data reference.

	 * In-Status:  EMPTY, EVICTABLE, EVICTED;
	 * Out-Status: MODIFY.
	 * 
	 * @param newData : the new matrix data reference
	 * @return the matrix data reference, which is the same as the argument
	 * @throws CacheException 
	 */
	@Override
	public synchronized MatrixBlock acquireModify(MatrixBlock newData)
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Acquire modify newdata "+getVarName());
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		if (! isAvailableToModify ())
			throw new CacheException ("MatrixObject not available to modify.");
		
		//clear old data 
		clearData(); 
		
		//cache status maintenance
		super.acquire (true, false); //no need to load evicted matrix
		setDirty(true);
		_isAcquireFromEmpty = false;
		
		//set references to new data
		if (newData == null)
			throw new CacheException("acquireModify with empty matrix block.");
		_data = newData; 
		updateStatusPinned(true);
		
		if( DMLScript.STATISTICS ){
			long t1 = System.nanoTime();
			CacheStatistics.incrementAcquireMTime(t1-t0);
		}
		
		return _data;
	}

	/**
	 * Releases the shared ("read-only") or exclusive ("write") lock.  Updates
	 * the matrix size, last-access time, metadata, etc.
	 * 
	 * Synchronized because there might be parallel threads (parfor local) that
	 * access the same MatrixObjectNew object (in case it was created before the loop).
	 * 
	 * In-Status:  READ, MODIFY;
	 * Out-Status: READ(-1), EVICTABLE, EMPTY.
	 * 
	 * @throws CacheException
	 */
	@Override
	public synchronized void release() 
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Release "+getVarName());
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		boolean write = false;
		if ( isModify() )
		{
			//set flags for write
			write = true;
			setDirty(true);
			
			//update meta data
			refreshMetaData();
		}

		//compact empty in-memory block 
		if( _data.isEmptyBlock(false) && _data.isAllocated() )
			_data.cleanupBlock(true, true);
		
		//cache status maintenance (pass cacheNoWrite flag)
		super.release(_isAcquireFromEmpty && !_requiresLocalWrite);
		updateStatusPinned(false);
		
		if(    isCachingActive() //only if caching is enabled (otherwise keep everything in mem)
			&& isCached(true)    //not empty and not read/modify
			&& !isUpdateInPlaceEnabled()    //pinned result variable
		    && !isBelowCachingThreshold() ) //min size for caching
		{
			if( write || _requiresLocalWrite ) 
			{
				//evict blob
				String filePath = getCacheFilePathAndName();
				try {
					LazyWriteBuffer.writeBlock(filePath, _data);
				}
				catch (Exception e)
				{
					throw new CacheException("Eviction to local path " + filePath + " ("+getVarName()+") failed.", e);
				}
				_requiresLocalWrite = false;
			}
			
			//create cache
			createCache();
			_data = null;			
		}
		else if( LOG.isTraceEnabled() ){
			LOG.trace("Var "+getVarName()+" not subject to caching: rows="+_data.getNumRows()+", cols="+_data.getNumColumns()+", state="+getStatusAsString());
		}

		if( DMLScript.STATISTICS ){
			long t1 = System.nanoTime();
			CacheStatistics.incrementReleaseTime(t1-t0);
		}
	}

	/**
	 * Sets the matrix data reference to <code>null</code>, abandons the old matrix.
	 * Makes the "envelope" empty.  Run it to finalize the matrix (otherwise the
	 * evicted matrix file may remain undeleted).
	 * 
	 * In-Status:  EMPTY, EVICTABLE, EVICTED;
	 * Out-Status: EMPTY.
	 * @throws CacheException 
	 */
	@Override
	public synchronized void clearData() 
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Clear data "+getVarName());
		
		// check if cleanup enabled and possible 
		if( !isCleanupEnabled() ) 
			return; // do nothing
		if( !isAvailableToModify() )
			throw new CacheException ("MatrixObject (" + this.getDebugName() + ") not available to modify. Status = " + this.getStatusAsString() + ".");
		
		// clear existing WB / FS representation (but prevent unnecessary probes)
		if( !(isEmpty(true)||(_data!=null && isBelowCachingThreshold()) 
			  ||(_data!=null && !isCachingActive()) )) //additional condition for JMLC
			freeEvictedBlob();	
		
		// clear the in-memory data
		_data = null;	
		clearCache();
		
		// clear rdd/broadcast back refs
		if( _rddHandle != null )
			_rddHandle.setBackReference(null);
		if( _bcHandle != null )
			_bcHandle.setBackReference(null);
		
		// change object state EMPTY
		setDirty(false);
		setEmpty();
	}
	
	/**
	 * Synchronized because there might be parallel threads (parfor local) that
	 * access the same MatrixObjectNew object (in case it was created before the loop).
	 * If all threads export the same data object concurrently it results in errors
	 * because they all write to the same file. Efficiency for loops and parallel threads
	 * is achieved by checking if the in-memory matrix block is dirty.
	 * 
	 * NOTE: MB: we do not use dfs copy from local (evicted) to HDFS because this would ignore
	 * the output format and most importantly would bypass reblocking during write (which effects the
	 * potential degree of parallelism). However, we copy files on HDFS if certain criteria are given.  
	 * 
	 * @param fName
	 * @param outputFormat
	 * @throws CacheException
	 */
	@Override
	public synchronized void exportData (String fName, String outputFormat, int replication, FileFormatProperties formatProperties)
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Export data "+getVarName()+" "+fName);
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		//prevent concurrent modifications
		if ( !isAvailableToRead() )
			throw new CacheException ("MatrixObject not available to read.");

		LOG.trace("Exporting " + this.getDebugName() + " to " + fName + " in format " + outputFormat);
				
		boolean pWrite = false; // !fName.equals(_hdfsFileName); //persistent write flag
		if ( fName.equals(_hdfsFileName) ) {
			setHDFSFileExists(true);
			pWrite = false;
		}
		else {
			pWrite = true;  // i.e., export is called from "write" instruction
		}

		//actual export (note: no direct transfer of local copy in order to ensure blocking (and hence, parallelism))
		if(  isDirty()  ||      //use dirty for skipping parallel exports
		    (pWrite && !isEqualOutputFormat(outputFormat)) ) 
		{		  
			// CASE 1: dirty in-mem matrix or pWrite w/ different format (write matrix to fname; load into memory if evicted)
			// a) get the matrix		
			if( isEmpty(true) )
			{
			    //read data from HDFS if required (never read before), this applies only to pWrite w/ different output formats
				//note: for large rdd outputs, we compile dedicated writespinstructions (no need to handle this here) 
				try
				{
					if( getRDDHandle()==null || getRDDHandle().allowsShortCircuitRead() )
						_data = readMatrixFromHDFS( _hdfsFileName );
					else
						_data = readMatrixFromRDD( getRDDHandle(), new MutableBoolean() );
					setDirty(false);
				}
				catch (IOException e)
				{
				    throw new CacheException("Reading of " + _hdfsFileName + " ("+getVarName()+") failed.", e);
				}
			}
			//get object from cache
			if( _data == null )
				getCache();
			super.acquire( false, _data==null ); //incl. read matrix if evicted	
			
			// b) write the matrix 
			try
			{
				writeMetaData( fName, outputFormat, formatProperties );
				writeMatrixToHDFS( fName, outputFormat, replication, formatProperties );
				if ( !pWrite )
					setDirty(false);
			}
			catch (Exception e)
			{
				throw new CacheException ("Export to " + fName + " failed.", e);
			}
			finally
			{
				release();
			}
		}
		else if( pWrite ) // pwrite with same output format
		{
			//CASE 2: matrix already in same format but different file on hdfs (copy matrix to fname)
			try
			{
				MapReduceTool.deleteFileIfExistOnHDFS(fName);
				MapReduceTool.deleteFileIfExistOnHDFS(fName+".mtd");
				if( getRDDHandle()==null || getRDDHandle().allowsShortCircuitRead() )
					MapReduceTool.copyFileOnHDFS( _hdfsFileName, fName );
				else //write might trigger rdd operations and nnz maintenance
					writeMatrixFromRDDtoHDFS(getRDDHandle(), fName, outputFormat);
				writeMetaData( fName, outputFormat, formatProperties );
			}
			catch (Exception e) {
				throw new CacheException ("Export to " + fName + " failed.", e);
			}
		}
		else if( getRDDHandle()!=null && //pending rdd operation
				!getRDDHandle().allowsShortCircuitRead() )
		{
			//CASE 3: pending rdd operation (other than checkpoints)
			try
			{
				writeMatrixFromRDDtoHDFS(getRDDHandle(), fName, outputFormat);
				writeMetaData( fName, outputFormat, formatProperties );
			}
			catch (Exception e) {
				throw new CacheException ("Export to " + fName + " failed.", e);
			}
		}
		else 
		{
			//CASE 4: data already in hdfs (do nothing, no need for export)
			LOG.trace(this.getDebugName() + ": Skip export to hdfs since data already exists.");
		}
		  
		if( DMLScript.STATISTICS ){
			long t1 = System.nanoTime();
			CacheStatistics.incrementExportTime(t1-t0);
		}
	}

	/**
	 * 
	 * @param fName
	 * @param outputFormat
	 * @return
	 * @throws CacheException
	 */
	public synchronized boolean moveData(String fName, String outputFormat) 
		throws CacheException 
	{	
		boolean ret = false;
		
		try
		{
			//ensure input file is persistent on hdfs (pending RDD operations), 
			//file might have been written during export or collect via write/read
			if( getRDDHandle() != null && !MapReduceTool.existsFileOnHDFS(_hdfsFileName) ) { 
				writeMatrixFromRDDtoHDFS(getRDDHandle(), _hdfsFileName, outputFormat);
			}
			
			//export or rename to target file on hdfs
			if( isDirty() || (!isEqualOutputFormat(outputFormat) && isEmpty(true))) 
			{
				exportData(fName, outputFormat);
				ret = true;
			}
			else if( isEqualOutputFormat(outputFormat) )
			{
				MapReduceTool.deleteFileIfExistOnHDFS(fName);
				MapReduceTool.deleteFileIfExistOnHDFS(fName+".mtd");
				MapReduceTool.renameFileOnHDFS( _hdfsFileName, fName );
				writeMetaData( fName, outputFormat, null );
				ret = true;
			}				
		}
		catch (Exception e)
		{
			throw new CacheException ("Move to " + fName + " failed.", e);
		}
		
		return ret;
	}
	
	
	// *********************************************
	// ***                                       ***
	// ***       HIGH-LEVEL PUBLIC METHODS       ***
	// ***     FOR PARTITIONED MATRIX ACCESS     ***
	// ***   (all other methods still usable)    ***
	// ***                                       ***
	// *********************************************
	
	/**
	 * @param n 
	 * 
	 */
	public void setPartitioned( PDataPartitionFormat format, int n )
	{
		_partitioned = true;
		_partitionFormat = format;
		_partitionSize = n;
	}
	

	public void unsetPartitioned() 
	{
		_partitioned = false;
		_partitionFormat = null;
		_partitionSize = -1;
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean isPartitioned()
	{
		return _partitioned;
	}
	
	public PDataPartitionFormat getPartitionFormat()
	{
		return _partitionFormat;
	}
	
	public int getPartitionSize()
	{
		return _partitionSize;
	}
	
	public synchronized void setInMemoryPartition(MatrixBlock block)
	{
		_partitionInMemory = block;
	}
	
	/**
	 * NOTE: for reading matrix partitions, we could cache (in its real sense) the read block
	 * with soft references (no need for eviction, as partitioning only applied for read-only matrices).
	 * However, since we currently only support row- and column-wise partitioning caching is not applied yet.
	 * This could be changed once we also support column-block-wise and row-block-wise. Furthermore,
	 * as we reject to partition vectors and support only full row or column indexing, no metadata (apart from
	 * the partition flag) is required.  
	 * 
	 * @param pred
	 * @return
	 * @throws CacheException
	 */
	public synchronized MatrixBlock readMatrixPartition( IndexRange pred ) 
		throws CacheException
	{
		if( LOG.isTraceEnabled() )
			LOG.trace("Acquire partition "+getVarName()+" "+pred);
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		
		if ( !_partitioned )
			throw new CacheException ("MatrixObject not available to indexed read.");
		
		//return static partition of set from outside of the program
		if( _partitionInMemory != null )
			return _partitionInMemory;
		
		MatrixBlock mb = null;
		
		try
		{
			boolean blockwise = (_partitionFormat==PDataPartitionFormat.ROW_BLOCK_WISE || _partitionFormat==PDataPartitionFormat.COLUMN_BLOCK_WISE);
			
			//preparations for block wise access
			MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
			MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
			int brlen = mc.getRowsPerBlock();
			int bclen = mc.getColsPerBlock();
			
			//get filename depending on format
			String fname = getPartitionFileName( pred, brlen, bclen );
			
			//probe cache
			if( blockwise && _partitionCacheName != null && _partitionCacheName.equals(fname) )
			{
				mb = _cache.get(); //try getting block from cache
			}
			
			if( mb == null ) //block not in cache
			{
				//get rows and cols
				long rows = -1;
				long cols = -1;
				switch( _partitionFormat )
				{
					case ROW_WISE:
						rows = 1;
						cols = mc.getCols();
						break;
					case ROW_BLOCK_WISE: 
						rows = brlen;
						cols = mc.getCols();
						break;
					case COLUMN_WISE:
						rows = mc.getRows();
						cols = 1;
						break;
					case COLUMN_BLOCK_WISE: 
						rows = mc.getRows();
						cols = bclen;
						break;
					default:
						throw new CacheException("Unsupported partition format: "+_partitionFormat);
				}
				
				
				//read the 
				if( MapReduceTool.existsFileOnHDFS(fname) )
					mb = readMatrixFromHDFS( fname, rows, cols );
				else
				{
					mb = new MatrixBlock((int)rows, (int)cols, true);
					LOG.warn("Reading empty matrix partition "+fname);
				}
			}
			
			//post processing
			if( blockwise )
			{
				//put block into cache
				_partitionCacheName = fname;
				_cache = new SoftReference<MatrixBlock>(mb);
				
				if( _partitionFormat == PDataPartitionFormat.ROW_BLOCK_WISE )
				{
					int rix = (int)((pred.rowStart-1)%brlen);
					mb = mb.sliceOperations(rix, rix, (int)(pred.colStart-1), (int)(pred.colEnd-1), new MatrixBlock());
				}
				if( _partitionFormat == PDataPartitionFormat.COLUMN_BLOCK_WISE )
				{
					int cix = (int)((pred.colStart-1)%bclen);
					mb = mb.sliceOperations((int)(pred.rowStart-1), (int)(pred.rowEnd-1), cix, cix, new MatrixBlock());
				}
			}
			
			//NOTE: currently no special treatment of non-existing partitions necessary 
			//      because empty blocks are written anyway
		}
		catch(Exception ex)
		{
			throw new CacheException(ex);
		}
		
		if( DMLScript.STATISTICS ){
			long t1 = System.nanoTime();
			CacheStatistics.incrementAcquireRTime(t1-t0);
		}
		
		return mb;
	}
	
	
	/**
	 * 
	 * @param pred
	 * @return
	 * @throws CacheException 
	 */
	public String getPartitionFileName( IndexRange pred, int brlen, int bclen ) 
		throws CacheException
	{
		if ( !_partitioned )
			throw new CacheException ("MatrixObject not available to indexed read.");
		
		StringBuilder sb = new StringBuilder();
		sb.append(_hdfsFileName);
		
		switch( _partitionFormat )
		{
			case ROW_WISE:
				sb.append(Lop.FILE_SEPARATOR);
				sb.append(pred.rowStart); 
				break;
			case ROW_BLOCK_WISE:
				sb.append(Lop.FILE_SEPARATOR);
				sb.append((pred.rowStart-1)/brlen+1);
				break;
			case COLUMN_WISE:
				sb.append(Lop.FILE_SEPARATOR);
				sb.append(pred.colStart);
				break;
			case COLUMN_BLOCK_WISE:
				sb.append(Lop.FILE_SEPARATOR);
				sb.append((pred.colStart-1)/bclen+1);
				break;
			default:
				throw new CacheException ("MatrixObject not available to indexed read.");
		}

		return sb.toString();
	}	
	
	

	// *********************************************
	// ***                                       ***
	// ***      LOW-LEVEL PROTECTED METHODS      ***
	// ***         EXTEND CACHEABLE DATA         ***
	// ***     ONLY CALLED BY THE SUPERCLASS     ***
	// ***                                       ***
	// *********************************************
		
	@Override
	protected MatrixBlock readBlobFromCache(String fname) throws IOException {
		return (MatrixBlock)LazyWriteBuffer.readBlock(fname, true);
	}
	
	
	// *******************************************
	// ***                                     ***
	// ***      LOW-LEVEL PRIVATE METHODS      ***
	// ***           FOR MATRIX I/O            ***
	// ***                                     ***
	// *******************************************
	
	/**
	 * 
	 * @param filePathAndName
	 * @return
	 * @throws IOException
	 */
	private MatrixBlock readMatrixFromHDFS(String filePathAndName)
		throws IOException
	{
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
		MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
		return readMatrixFromHDFS( filePathAndName, mc.getRows(), mc.getCols() );
	}
	
	/**
	 * 
	 * @param rdd
	 * @return
	 * @throws IOException 
	 */
	private MatrixBlock readMatrixFromRDD(RDDObject rdd, MutableBoolean writeStatus) 
		throws IOException
	{
		//note: the read of a matrix block from an RDD might trigger
		//lazy evaluation of pending transformations.
		RDDObject lrdd = rdd;

		//prepare return status (by default only collect)
		writeStatus.setValue(false);
		
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
		MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
		InputInfo ii = iimd.getInputInfo();
		MatrixBlock mb = null;
		try 
		{
			//prevent unnecessary collect through rdd checkpoint
			if( rdd.allowsShortCircuitCollect() ) {
				lrdd = (RDDObject)rdd.getLineageChilds().get(0);
			}
			
			//obtain matrix block from RDD
			int rlen = (int)mc.getRows();
			int clen = (int)mc.getCols();
			int brlen = (int)mc.getRowsPerBlock();
			int bclen = (int)mc.getColsPerBlock();
			long nnz = mc.getNonZeros();
			
			//guarded rdd collect 
			if( ii == InputInfo.BinaryBlockInputInfo && //guarded collect not for binary cell
				!OptimizerUtils.checkSparkCollectMemoryBudget(rlen, clen, brlen, bclen, nnz, sizePinned.get()) ) {
				//write RDD to hdfs and read to prevent invalid collect mem consumption 
				//note: lazy, partition-at-a-time collect (toLocalIterator) was significantly slower
				if( !MapReduceTool.existsFileOnHDFS(_hdfsFileName) ) { //prevent overwrite existing file
					long newnnz = SparkExecutionContext.writeRDDtoHDFS(lrdd, _hdfsFileName, iimd.getOutputInfo());
					((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics().setNonZeros(newnnz);
					((RDDObject)rdd).setHDFSFile(true); //mark rdd as hdfs file (for restore)
					writeStatus.setValue(true);         //mark for no cache-write on read
				}
				mb = readMatrixFromHDFS(_hdfsFileName);
			}
			else if( ii == InputInfo.BinaryCellInputInfo ) {
				//collect matrix block from binary block RDD
				mb = SparkExecutionContext.toMatrixBlock(lrdd, rlen, clen, nnz);		
			}
			else {
				//collect matrix block from binary cell RDD
				mb = SparkExecutionContext.toMatrixBlock(lrdd, rlen, clen, brlen, bclen, nnz);	
			}
		}
		catch(DMLRuntimeException ex) {
			throw new IOException(ex);
		}
		
		//sanity check correct output
		if( mb == null ) {
			throw new IOException("Unable to load matrix from rdd: "+lrdd.getVarName());
		}
		
		return mb;
	}
	
	/**
	 * 
	 * @param rdd
	 * @param fname
	 * @param outputFormat
	 * @throws DMLRuntimeException 
	 */
	private void writeMatrixFromRDDtoHDFS(RDDObject rdd, String fname, String outputFormat) 
	    throws DMLRuntimeException
	{
	    //prepare output info
        MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
	    OutputInfo oinfo = (outputFormat != null ? OutputInfo.stringToOutputInfo (outputFormat) 
                : InputInfo.getMatchingOutputInfo (iimd.getInputInfo ()));
	    
		//note: the write of an RDD to HDFS might trigger
		//lazy evaluation of pending transformations.				
		long newnnz = SparkExecutionContext.writeRDDtoHDFS(rdd, fname, oinfo);	
		((MatrixDimensionsMetaData) _metaData).getMatrixCharacteristics().setNonZeros(newnnz);
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param rlen
	 * @param clen
	 * @return
	 * @throws IOException
	 */
	private MatrixBlock readMatrixFromHDFS(String filePathAndName, long rlen, long clen)
		throws IOException
	{
		long begin = 0;
		
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
		MatrixCharacteristics mc = iimd.getMatrixCharacteristics();
		
		if( LOG.isTraceEnabled() ){
			LOG.trace("Reading matrix from HDFS...  " + getVarName() + "  Path: " + filePathAndName 
					+ ", dimensions: [" + mc.getRows() + ", " + mc.getCols() + ", " + mc.getNonZeros() + "]");
			begin = System.currentTimeMillis();
		}
			
		double sparsity = ( mc.getNonZeros() >= 0 ? ((double)mc.getNonZeros())/(mc.getRows()*mc.getCols()) : 1.0d) ; //expected sparsity
		MatrixBlock newData = DataConverter.readMatrixFromHDFS(filePathAndName, iimd.getInputInfo(),
				rlen, clen, mc.getRowsPerBlock(), mc.getColsPerBlock(), sparsity, getFileFormatProperties());
		
		//sanity check correct output
		if( newData == null ) {
			throw new IOException("Unable to load matrix from file: "+filePathAndName);
		}
		
		if( LOG.isTraceEnabled() )
			LOG.trace("Reading Completed: " + (System.currentTimeMillis()-begin) + " msec.");
		
		return newData;
	}

	/**
	 * Writes in-memory matrix to HDFS in a specified format.
	 * 
	 * @throws DMLRuntimeException
	 * @throws IOException
	 */
	private void writeMatrixToHDFS (String filePathAndName, String outputFormat, int replication, FileFormatProperties formatProperties)
		throws DMLRuntimeException, IOException
	{
		long begin = 0;
		if( LOG.isTraceEnabled() ){
			LOG.trace (" Writing matrix to HDFS...  " + getVarName() + "  Path: " + filePathAndName + ", Format: " +
						(outputFormat != null ? outputFormat : "inferred from metadata"));
			begin = System.currentTimeMillis();
		}
		
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;

		if (_data != null)
		{
			// Get the dimension information from the metadata stored within MatrixObject
			MatrixCharacteristics mc = iimd.getMatrixCharacteristics ();
			// Write the matrix to HDFS in requested format
			OutputInfo oinfo = (outputFormat != null ? OutputInfo.stringToOutputInfo (outputFormat) 
					                                 : InputInfo.getMatchingOutputInfo (iimd.getInputInfo ()));
			
			// when outputFormat is binaryblock, make sure that matrixCharacteristics has correct blocking dimensions
			// note: this is only required if singlenode (due to binarycell default) 
			if ( oinfo == OutputInfo.BinaryBlockOutputInfo && DMLScript.rtplatform == RUNTIME_PLATFORM.SINGLE_NODE &&
				(mc.getRowsPerBlock() != ConfigurationManager.getBlocksize() || mc.getColsPerBlock() != ConfigurationManager.getBlocksize()) ) 
			{
				DataConverter.writeMatrixToHDFS(_data, filePathAndName, oinfo, new MatrixCharacteristics(mc.getRows(), mc.getCols(), ConfigurationManager.getBlocksize(), ConfigurationManager.getBlocksize(), mc.getNonZeros()), replication, formatProperties);
			}
			else {
				DataConverter.writeMatrixToHDFS(_data, filePathAndName, oinfo, mc, replication, formatProperties);
			}

			if( LOG.isTraceEnabled() )
				LOG.trace("Writing matrix to HDFS ("+filePathAndName+") - COMPLETED... " + (System.currentTimeMillis()-begin) + " msec.");
		}
		else if( LOG.isTraceEnabled() ) 
		{
			LOG.trace ("Writing matrix to HDFS ("+filePathAndName+") - NOTHING TO WRITE (_data == null).");
		}
		
		if( DMLScript.STATISTICS )
			CacheStatistics.incrementHDFSWrites();
	}
	
	/**
	 * 
	 * @param filePathAndName
	 * @param outputFormat
	 * @throws DMLRuntimeException
	 * @throws IOException
	 */
	private void writeMetaData (String filePathAndName, String outputFormat, FileFormatProperties formatProperties)
		throws DMLRuntimeException, IOException
	{
		MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
	
		if (iimd != null)
		{
			// Write the matrix to HDFS in requested format			
			OutputInfo oinfo = (outputFormat != null ? OutputInfo.stringToOutputInfo (outputFormat) 
                    : InputInfo.getMatchingOutputInfo (iimd.getInputInfo ()));
			
			if ( oinfo != OutputInfo.MatrixMarketOutputInfo ) {
				// Get the dimension information from the metadata stored within MatrixObject
				MatrixCharacteristics mc = iimd.getMatrixCharacteristics ();
				
				// when outputFormat is binaryblock, make sure that matrixCharacteristics has correct blocking dimensions
				// note: this is only required if singlenode (due to binarycell default) 
				if ( oinfo == OutputInfo.BinaryBlockOutputInfo && DMLScript.rtplatform == RUNTIME_PLATFORM.SINGLE_NODE &&
					(mc.getRowsPerBlock() != ConfigurationManager.getBlocksize() || mc.getColsPerBlock() != ConfigurationManager.getBlocksize()) ) 
				{
					mc = new MatrixCharacteristics(mc.getRows(), mc.getCols(), ConfigurationManager.getBlocksize(), ConfigurationManager.getBlocksize(), mc.getNonZeros());
				}
				MapReduceTool.writeMetaDataFile (filePathAndName + ".mtd", valueType, mc, oinfo, formatProperties);
			}
		}
		else {
			throw new DMLRuntimeException("Unexpected error while writing mtd file (" + filePathAndName + ") -- metadata is null.");
		}
	}
	
	/**
	 * 
	 * @param outputFormat
	 * @return
	 */
	private boolean isEqualOutputFormat( String outputFormat )
	{
		boolean ret = true;
		
		if( outputFormat != null )
		{
			try
			{
				MatrixFormatMetaData iimd = (MatrixFormatMetaData) _metaData;
				OutputInfo oi1 = InputInfo.getMatchingOutputInfo( iimd.getInputInfo() );
				OutputInfo oi2 = OutputInfo.stringToOutputInfo( outputFormat );
				if( oi1 != oi2 )
				{
					ret = false;
				}
			}
			catch(Exception ex)
			{
				ret = false;
			}
		}
		
		return ret;
	}

	/**
	 * 
	 * @param add
	 */
	private void updateStatusPinned(boolean add) {
		if( _data != null ) { //data should never be null
			long size = sizePinned.get();
			size += (add ? 1 : -1) * _data.getInMemorySize();
			sizePinned.set( Math.max(size,0) );
		}
	}
	
	/**
	 * 
	 * @param flag
	 */
	public void enableUpdateInPlace(boolean flag) {
		_updateInPlaceFlag = flag;
	}
	
	/**
	 * 
	 * @return
	 */
	public boolean isUpdateInPlaceEnabled() {
		return _updateInPlaceFlag;
	}

	/**
	 * 
	 */
	public void setEmptyStatus() {
		setEmpty();
	}
}
