package org.apache.lucene.index;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader.FieldOption;
import org.apache.lucene.index.MergePolicy.MergeAbortedException;
import org.apache.lucene.index.codecs.Codecs;
import org.apache.lucene.index.codecs.Codec;
import org.apache.lucene.index.codecs.FieldsConsumer;
import org.apache.lucene.index.codecs.TermsConsumer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.index.codecs.DocsConsumer;
import org.apache.lucene.index.codecs.PositionsConsumer;

/**
 * The SegmentMerger class combines two or more Segments, represented by an IndexReader ({@link #add},
 * into a single Segment.  After adding the appropriate readers, call the merge method to combine the 
 * segments.
 *<P> 
 * If the compoundFile flag is set, then the segments will be merged into a compound file.
 *   
 * 
 * @see #merge
 * @see #add
 */
final class SegmentMerger {
  
  /** norms header placeholder */
  static final byte[] NORMS_HEADER = new byte[]{'N','R','M',-1}; 
  
  private Directory directory;
  private String segment;
  private int termIndexInterval = IndexWriter.DEFAULT_TERM_INDEX_INTERVAL;

  private List<IndexReader> readers = new ArrayList<IndexReader>();
  private FieldInfos fieldInfos;
  
  private int mergedDocs;

  private final CheckAbort checkAbort;

  // Whether we should merge doc stores (stored fields and
  // vectors files).  When all segments we are merging
  // already share the same doc store files, we don't need
  // to merge the doc stores.
  private boolean mergeDocStores;

  /** Maximum number of contiguous documents to bulk-copy
      when merging stored fields */
  private final static int MAX_RAW_MERGE_DOCS = 4192;
  
  private final Codecs codecs;
  private Codec codec;

  /** This ctor used only by test code.
   * 
   * @param dir The Directory to merge the other segments into
   * @param name The name of the new segment
   */
  SegmentMerger(Directory dir, String name) {
    directory = dir;
    segment = name;
    codecs = Codecs.getDefault();
    checkAbort = new CheckAbort(null, null) {
      @Override
      public void work(double units) throws MergeAbortedException {
        // do nothing
      }
    };
  }

  SegmentMerger(IndexWriter writer, String name, MergePolicy.OneMerge merge, Codecs codecs) {
    directory = writer.getDirectory();
    this.codecs = codecs;
    segment = name;
    if (merge != null) {
      checkAbort = new CheckAbort(merge, directory);
    } else {
      checkAbort = new CheckAbort(null, null) {
        @Override
        public void work(double units) throws MergeAbortedException {
          // do nothing
        }
      };
    }
    termIndexInterval = writer.getTermIndexInterval();
  }
  
  boolean hasProx() {
    return fieldInfos.hasProx();
  }

  /**
   * Add an IndexReader to the collection of readers that are to be merged
   * @param reader
   */
  final void add(IndexReader reader) {
    readers.add(reader);
  }

  /**
   * 
   * @param i The index of the reader to return
   * @return The ith reader to be merged
   */
  final IndexReader segmentReader(int i) {
    return readers.get(i);
  }

  /**
   * Merges the readers specified by the {@link #add} method into the directory passed to the constructor
   * @return The number of documents that were merged
   * @throws CorruptIndexException if the index is corrupt
   * @throws IOException if there is a low-level IO error
   */
  final int merge() throws CorruptIndexException, IOException {
    return merge(true);
  }

  /**
   * Merges the readers specified by the {@link #add} method
   * into the directory passed to the constructor.
   * @param mergeDocStores if false, we will not merge the
   * stored fields nor vectors files
   * @return The number of documents that were merged
   * @throws CorruptIndexException if the index is corrupt
   * @throws IOException if there is a low-level IO error
   */
  final int merge(boolean mergeDocStores) throws CorruptIndexException, IOException {

    this.mergeDocStores = mergeDocStores;
    
    // NOTE: it's important to add calls to
    // checkAbort.work(...) if you make any changes to this
    // method that will spend alot of time.  The frequency
    // of this check impacts how long
    // IndexWriter.close(false) takes to actually stop the
    // threads.

    mergedDocs = mergeFields();
    mergeTerms();
    mergeNorms();

    if (mergeDocStores && fieldInfos.hasVectors())
      mergeVectors();

    return mergedDocs;
  }

  /**
   * close all IndexReaders that have been added.
   * Should not be called before merge().
   * @throws IOException
   */
  final void closeReaders() throws IOException {
    for (final IndexReader reader : readers) {
      reader.close();
    }
  }

  final List<String> createCompoundFile(String fileName) throws IOException {
    // nocommit -- messy!
    final SegmentWriteState state = new SegmentWriteState(null, directory, segment, fieldInfos, null, mergedDocs, 0, 0, Codecs.getDefault());
    return createCompoundFile(fileName, new SegmentInfo(segment, mergedDocs, directory,
                                                        Codecs.getDefault().getWriter(state)));
  }

  final List<String> createCompoundFile(String fileName, final SegmentInfo info)
          throws IOException {
    CompoundFileWriter cfsWriter = new CompoundFileWriter(directory, fileName, checkAbort);

    List<String> files = new ArrayList<String>();

    // Basic files
    for (int i = 0; i < IndexFileNames.COMPOUND_EXTENSIONS_NOT_CODEC.length; i++) {
      String ext = IndexFileNames.COMPOUND_EXTENSIONS_NOT_CODEC[i];
       
      // nocommit
      /*
      if (ext.equals(IndexFileNames.PROX_EXTENSION) && !hasProx())
        continue;
        
      */

      if (mergeDocStores || (!ext.equals(IndexFileNames.FIELDS_EXTENSION) &&
                             !ext.equals(IndexFileNames.FIELDS_INDEX_EXTENSION)))
        files.add(segment + "." + ext);
    }

    codec.files(directory, info, files);
    
    // Fieldable norm files
    for (int i = 0; i < fieldInfos.size(); i++) {
      FieldInfo fi = fieldInfos.fieldInfo(i);
      if (fi.isIndexed && !fi.omitNorms) {
        files.add(segment + "." + IndexFileNames.NORMS_EXTENSION);
        break;
      }
    }

    // Vector files
    if (fieldInfos.hasVectors() && mergeDocStores) {
      for (int i = 0; i < IndexFileNames.VECTOR_EXTENSIONS.length; i++) {
        files.add(segment + "." + IndexFileNames.VECTOR_EXTENSIONS[i]);
      }
    }

    // Now merge all added files
    for (String file : files) {
      cfsWriter.addFile(file);
    }
    
    // Perform the merge
    cfsWriter.close();
   
    return files;
  }

  private void addIndexed(IndexReader reader, FieldInfos fInfos,
      Collection<String> names, boolean storeTermVectors,
      boolean storePositionWithTermVector, boolean storeOffsetWithTermVector,
      boolean storePayloads, boolean omitTFAndPositions)
      throws IOException {
    for (String field : names) {
      fInfos.add(field, true, storeTermVectors,
          storePositionWithTermVector, storeOffsetWithTermVector, !reader
              .hasNorms(field), storePayloads, omitTFAndPositions);
    }
  }

  private SegmentReader[] matchingSegmentReaders;
  private int[] rawDocLengths;
  private int[] rawDocLengths2;

  private void setMatchingSegmentReaders() {
    // If the i'th reader is a SegmentReader and has
    // identical fieldName -> number mapping, then this
    // array will be non-null at position i:
    int numReaders = readers.size();
    matchingSegmentReaders = new SegmentReader[numReaders];

    // If this reader is a SegmentReader, and all of its
    // field name -> number mappings match the "merged"
    // FieldInfos, then we can do a bulk copy of the
    // stored fields:
    for (int i = 0; i < numReaders; i++) {
      IndexReader reader = readers.get(i);
      if (reader instanceof SegmentReader) {
        SegmentReader segmentReader = (SegmentReader) reader;
        boolean same = true;
        FieldInfos segmentFieldInfos = segmentReader.fieldInfos();
        int numFieldInfos = segmentFieldInfos.size();
        for (int j = 0; same && j < numFieldInfos; j++) {
          same = fieldInfos.fieldName(j).equals(segmentFieldInfos.fieldName(j));
        }
        if (same) {
          matchingSegmentReaders[i] = segmentReader;
        }
      }
    }

    // Used for bulk-reading raw bytes for stored fields
    rawDocLengths = new int[MAX_RAW_MERGE_DOCS];
    rawDocLengths2 = new int[MAX_RAW_MERGE_DOCS];
  }

  /**
   * 
   * @return The number of documents in all of the readers
   * @throws CorruptIndexException if the index is corrupt
   * @throws IOException if there is a low-level IO error
   */
  private final int mergeFields() throws CorruptIndexException, IOException {

    if (!mergeDocStores) {
      // When we are not merging by doc stores, their field
      // name -> number mapping are the same.  So, we start
      // with the fieldInfos of the last segment in this
      // case, to keep that numbering.
      final SegmentReader sr = (SegmentReader) readers.get(readers.size()-1);
      fieldInfos = (FieldInfos) sr.core.fieldInfos.clone();
    } else {
      fieldInfos = new FieldInfos();		  // merge field names
    }

    for (IndexReader reader : readers) {
      if (reader instanceof SegmentReader) {
        SegmentReader segmentReader = (SegmentReader) reader;
        FieldInfos readerFieldInfos = segmentReader.fieldInfos();
        int numReaderFieldInfos = readerFieldInfos.size();
        for (int j = 0; j < numReaderFieldInfos; j++) {
          FieldInfo fi = readerFieldInfos.fieldInfo(j);
          fieldInfos.add(fi.name, fi.isIndexed, fi.storeTermVector,
              fi.storePositionWithTermVector, fi.storeOffsetWithTermVector,
              !reader.hasNorms(fi.name), fi.storePayloads,
              fi.omitTermFreqAndPositions);
        }
      } else {
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.TERMVECTOR_WITH_POSITION_OFFSET), true, true, true, false, false);
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.TERMVECTOR_WITH_POSITION), true, true, false, false, false);
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.TERMVECTOR_WITH_OFFSET), true, false, true, false, false);
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.TERMVECTOR), true, false, false, false, false);
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.OMIT_TERM_FREQ_AND_POSITIONS), false, false, false, false, true);
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.STORES_PAYLOADS), false, false, false, true, false);
        addIndexed(reader, fieldInfos, reader.getFieldNames(FieldOption.INDEXED), false, false, false, false, false);
        fieldInfos.add(reader.getFieldNames(FieldOption.UNINDEXED), false);
      }
    }
    fieldInfos.write(directory, segment + ".fnm");

    int docCount = 0;

    setMatchingSegmentReaders();

    if (mergeDocStores) {
      // merge field values
      final FieldsWriter fieldsWriter = new FieldsWriter(directory, segment, fieldInfos);

      try {
        int idx = 0;
        for (IndexReader reader : readers) {
          final SegmentReader matchingSegmentReader = matchingSegmentReaders[idx++];
          FieldsReader matchingFieldsReader = null;
          if (matchingSegmentReader != null) {
            final FieldsReader fieldsReader = matchingSegmentReader.getFieldsReader();
            if (fieldsReader != null && fieldsReader.canReadRawDocs()) {            
              matchingFieldsReader = fieldsReader;
            }
          }
          if (reader.hasDeletions()) {
            docCount += copyFieldsWithDeletions(fieldsWriter,
                                                reader, matchingFieldsReader);
          } else {
            docCount += copyFieldsNoDeletions(fieldsWriter,
                                              reader, matchingFieldsReader);
          }
        }
      } finally {
        fieldsWriter.close();
      }

      final String fileName = segment + "." + IndexFileNames.FIELDS_INDEX_EXTENSION;
      final long fdxFileLength = directory.fileLength(fileName);

      if (4+((long) docCount)*8 != fdxFileLength)
        // This is most likely a bug in Sun JRE 1.6.0_04/_05;
        // we detect that the bug has struck, here, and
        // throw an exception to prevent the corruption from
        // entering the index.  See LUCENE-1282 for
        // details.
        throw new RuntimeException("mergeFields produced an invalid result: docCount is " + docCount + " but fdx file size is " + fdxFileLength + " file=" + fileName + " file exists?=" + directory.fileExists(fileName) + "; now aborting this merge to prevent index corruption");

    } else
      // If we are skipping the doc stores, that means there
      // are no deletions in any of these segments, so we
      // just sum numDocs() of each segment to get total docCount
      for (final IndexReader reader : readers) {
        docCount += reader.numDocs();
      }

    return docCount;
  }

  private int copyFieldsWithDeletions(final FieldsWriter fieldsWriter, final IndexReader reader,
                                      final FieldsReader matchingFieldsReader)
    throws IOException, MergeAbortedException, CorruptIndexException {
    int docCount = 0;
    final int maxDoc = reader.maxDoc();
    if (matchingFieldsReader != null) {
      // We can bulk-copy because the fieldInfos are "congruent"
      for (int j = 0; j < maxDoc;) {
        if (reader.isDeleted(j)) {
          // skip deleted docs
          ++j;
          continue;
        }
        // We can optimize this case (doing a bulk byte copy) since the field 
        // numbers are identical
        int start = j, numDocs = 0;
        do {
          j++;
          numDocs++;
          if (j >= maxDoc) break;
          if (reader.isDeleted(j)) {
            j++;
            break;
          }
        } while(numDocs < MAX_RAW_MERGE_DOCS);
        
        IndexInput stream = matchingFieldsReader.rawDocs(rawDocLengths, start, numDocs);
        fieldsWriter.addRawDocuments(stream, rawDocLengths, numDocs);
        docCount += numDocs;
        checkAbort.work(300 * numDocs);
      }
    } else {
      for (int j = 0; j < maxDoc; j++) {
        if (reader.isDeleted(j)) {
          // skip deleted docs
          continue;
        }
        // NOTE: it's very important to first assign to doc then pass it to
        // termVectorsWriter.addAllDocVectors; see LUCENE-1282
        Document doc = reader.document(j);
        fieldsWriter.addDocument(doc);
        docCount++;
        checkAbort.work(300);
      }
    }
    return docCount;
  }

  private int copyFieldsNoDeletions(final FieldsWriter fieldsWriter, final IndexReader reader,
                                    final FieldsReader matchingFieldsReader)
    throws IOException, MergeAbortedException, CorruptIndexException {
    final int maxDoc = reader.maxDoc();
    int docCount = 0;
    if (matchingFieldsReader != null) {
      // We can bulk-copy because the fieldInfos are "congruent"
      while (docCount < maxDoc) {
        int len = Math.min(MAX_RAW_MERGE_DOCS, maxDoc - docCount);
        IndexInput stream = matchingFieldsReader.rawDocs(rawDocLengths, docCount, len);
        fieldsWriter.addRawDocuments(stream, rawDocLengths, len);
        docCount += len;
        checkAbort.work(300 * len);
      }
    } else {
      for (; docCount < maxDoc; docCount++) {
        // NOTE: it's very important to first assign to doc then pass it to
        // termVectorsWriter.addAllDocVectors; see LUCENE-1282
        Document doc = reader.document(docCount);
        fieldsWriter.addDocument(doc);
        checkAbort.work(300);
      }
    }
    return docCount;
  }

  /**
   * Merge the TermVectors from each of the segments into the new one.
   * @throws IOException
   */
  private final void mergeVectors() throws IOException {
    TermVectorsWriter termVectorsWriter = 
      new TermVectorsWriter(directory, segment, fieldInfos);

    try {
      int idx = 0;
      for (final IndexReader reader : readers) {
        final SegmentReader matchingSegmentReader = matchingSegmentReaders[idx++];
        TermVectorsReader matchingVectorsReader = null;
        if (matchingSegmentReader != null) {
          TermVectorsReader vectorsReader = matchingSegmentReader.getTermVectorsReaderOrig();

          // If the TV* files are an older format then they cannot read raw docs:
          if (vectorsReader != null && vectorsReader.canReadRawDocs()) {
            matchingVectorsReader = vectorsReader;
          }
        }
        if (reader.hasDeletions()) {
          copyVectorsWithDeletions(termVectorsWriter, matchingVectorsReader, reader);
        } else {
          copyVectorsNoDeletions(termVectorsWriter, matchingVectorsReader, reader);
          
        }
      }
    } finally {
      termVectorsWriter.close();
    }

    final String fileName = segment + "." + IndexFileNames.VECTORS_INDEX_EXTENSION;
    final long tvxSize = directory.fileLength(fileName);

    if (4+((long) mergedDocs)*16 != tvxSize)
      // This is most likely a bug in Sun JRE 1.6.0_04/_05;
      // we detect that the bug has struck, here, and
      // throw an exception to prevent the corruption from
      // entering the index.  See LUCENE-1282 for
      // details.
      throw new RuntimeException("mergeVectors produced an invalid result: mergedDocs is " + mergedDocs + " but tvx size is " + tvxSize + " file=" + fileName + " file exists?=" + directory.fileExists(fileName) + "; now aborting this merge to prevent index corruption");
  }

  private void copyVectorsWithDeletions(final TermVectorsWriter termVectorsWriter,
                                        final TermVectorsReader matchingVectorsReader,
                                        final IndexReader reader)
    throws IOException, MergeAbortedException {
    final int maxDoc = reader.maxDoc();
    if (matchingVectorsReader != null) {
      // We can bulk-copy because the fieldInfos are "congruent"
      for (int docNum = 0; docNum < maxDoc;) {
        if (reader.isDeleted(docNum)) {
          // skip deleted docs
          ++docNum;
          continue;
        }
        // We can optimize this case (doing a bulk byte copy) since the field 
        // numbers are identical
        int start = docNum, numDocs = 0;
        do {
          docNum++;
          numDocs++;
          if (docNum >= maxDoc) break;
          if (reader.isDeleted(docNum)) {
            docNum++;
            break;
          }
        } while(numDocs < MAX_RAW_MERGE_DOCS);
        
        matchingVectorsReader.rawDocs(rawDocLengths, rawDocLengths2, start, numDocs);
        termVectorsWriter.addRawDocuments(matchingVectorsReader, rawDocLengths, rawDocLengths2, numDocs);
        checkAbort.work(300 * numDocs);
      }
    } else {
      for (int docNum = 0; docNum < maxDoc; docNum++) {
        if (reader.isDeleted(docNum)) {
          // skip deleted docs
          continue;
        }
        
        // NOTE: it's very important to first assign to vectors then pass it to
        // termVectorsWriter.addAllDocVectors; see LUCENE-1282
        TermFreqVector[] vectors = reader.getTermFreqVectors(docNum);
        termVectorsWriter.addAllDocVectors(vectors);
        checkAbort.work(300);
      }
    }
  }
  
  private void copyVectorsNoDeletions(final TermVectorsWriter termVectorsWriter,
                                      final TermVectorsReader matchingVectorsReader,
                                      final IndexReader reader)
      throws IOException, MergeAbortedException {
    final int maxDoc = reader.maxDoc();
    if (matchingVectorsReader != null) {
      // We can bulk-copy because the fieldInfos are "congruent"
      int docCount = 0;
      while (docCount < maxDoc) {
        int len = Math.min(MAX_RAW_MERGE_DOCS, maxDoc - docCount);
        matchingVectorsReader.rawDocs(rawDocLengths, rawDocLengths2, docCount, len);
        termVectorsWriter.addRawDocuments(matchingVectorsReader, rawDocLengths, rawDocLengths2, len);
        docCount += len;
        checkAbort.work(300 * len);
      }
    } else {
      for (int docNum = 0; docNum < maxDoc; docNum++) {
        // NOTE: it's very important to first assign to vectors then pass it to
        // termVectorsWriter.addAllDocVectors; see LUCENE-1282
        TermFreqVector[] vectors = reader.getTermFreqVectors(docNum);
        termVectorsWriter.addAllDocVectors(vectors);
        checkAbort.work(300);
      }
    }
  }

  private SegmentFieldMergeQueue fieldsQueue;
  private SegmentMergeQueue termsQueue;
  
  Codec getCodec() {
    return codec;
  }

  private final void mergeTerms() throws CorruptIndexException, IOException {

    SegmentWriteState state = new SegmentWriteState(null, directory, segment, fieldInfos, null, mergedDocs, 0, termIndexInterval, codecs);

    // Let Codecs decide which codec will be used to write
    // this segment:
    codec = codecs.getWriter(state);
    
    final FieldsConsumer consumer = codec.fieldsConsumer(state);

    try {
      fieldsQueue = new SegmentFieldMergeQueue(readers.size());
      termsQueue = new SegmentMergeQueue(readers.size());
      mergeTermInfos(consumer);
    } finally {
      consumer.close();
    }
  }

  boolean omitTermFreqAndPositions;

  private final void mergeTermInfos(final FieldsConsumer consumer) throws CorruptIndexException, IOException {
    int base = 0;
    final int readerCount = readers.size();
    for (int i = 0; i < readerCount; i++) {
      IndexReader reader = readers.get(i);
      SegmentMergeInfo smi = new SegmentMergeInfo(base, reader);
      int[] docMap  = smi.getDocMap();
      if (docMap != null) {
        if (docMaps == null) {
          docMaps = new int[readerCount][];
          delCounts = new int[readerCount];
        }
        docMaps[i] = docMap;
        delCounts[i] = smi.reader.maxDoc() - smi.reader.numDocs();
      }
      
      base += reader.numDocs();

      assert reader.numDocs() == reader.maxDoc() - smi.delCount;

      if (smi.nextField()) {
        fieldsQueue.add(smi);				  // initialize queue
      } else {
        // segment is done: it has no fields
      }
    }

    SegmentMergeInfo[] match = new SegmentMergeInfo[readers.size()];

    while (fieldsQueue.size() > 0) {

      while(true) {
        SegmentMergeInfo smi = fieldsQueue.pop();
        if (smi.nextTerm()) {
          termsQueue.add(smi);
        } else if (smi.nextField()) {
          // field had no terms
          fieldsQueue.add(smi);
        } else {
          // done with a segment
        }
        SegmentMergeInfo top = fieldsQueue.top();
        if (top == null || (termsQueue.size() > 0 && ((SegmentMergeInfo) termsQueue.top()).field != top.field)) {
          break;
        }
      }
        
      if (termsQueue.size() > 0) {          
        // merge one field

        final String field  = termsQueue.top().field;
        if (Codec.DEBUG) {
          System.out.println("merge field=" + field + " segCount=" + termsQueue.size());
        }
        final FieldInfo fieldInfo = fieldInfos.fieldInfo(field);
        final TermsConsumer termsConsumer = consumer.addField(fieldInfo);
        omitTermFreqAndPositions = fieldInfo.omitTermFreqAndPositions;

        while(termsQueue.size() > 0) {
          // pop matching terms
          int matchSize = 0;
          while(true) {
            match[matchSize++] = termsQueue.pop();
            SegmentMergeInfo top = termsQueue.top();
            if (top == null || !top.term.termEquals(match[0].term)) {
              break;
            }
          }

          if (Codec.DEBUG) {
            System.out.println("merge field=" + field + " term=" + match[0].term + " numReaders=" + matchSize);
          }

          int df = appendPostings(termsConsumer, match, matchSize);

          checkAbort.work(df/3.0);

          // put SegmentMergeInfos back into repsective queues
          while (matchSize > 0) {
            SegmentMergeInfo smi = match[--matchSize];
            if (smi.nextTerm()) {
              termsQueue.add(smi);
            } else if (smi.nextField()) {
              fieldsQueue.add(smi);
            } else {
              // done with a segment
            }
          }
        }
        termsConsumer.finish();
      }
    }
  }

  private byte[] payloadBuffer;
  private int[][] docMaps;
  int[][] getDocMaps() {
    return docMaps;
  }
  private int[] delCounts;
  int[] getDelCounts() {
    return delCounts;
  }
  
  private final UnicodeUtil.UTF16Result termBuffer = new UnicodeUtil.UTF16Result();

  /** Process postings from multiple segments all positioned on the
   *  same term. Writes out merged entries into freqOutput and
   *  the proxOutput streams.
   *
   * @param smis array of segments
   * @param n number of cells in the array actually occupied
   * @return number of documents across all segments where this term was found
   * @throws CorruptIndexException if the index is corrupt
   * @throws IOException if there is a low-level IO error
   */
  private final int appendPostings(final TermsConsumer termsConsumer, SegmentMergeInfo[] smis, int n)
        throws CorruptIndexException, IOException {

    // nocommit -- maybe cutover TermsConsumer API to
    // TermRef as well?
    final TermRef text = smis[0].term;
    UnicodeUtil.UTF8toUTF16(text.bytes, text.offset, text.length, termBuffer);

    // Make space for terminator
    final int length = termBuffer.length;
    termBuffer.setLength(1+termBuffer.length);

    // nocommit -- make this a static final constant somewhere:
    termBuffer.result[length] = 0xffff;

    final DocsConsumer docConsumer = termsConsumer.startTerm(termBuffer.result, 0);

    int df = 0;
    for (int i = 0; i < n; i++) {
      if (Codec.DEBUG) {
        System.out.println("    merge reader " + (i+1) + " of " + n + ": term=" + text);
      }

      SegmentMergeInfo smi = smis[i];
      DocsEnum docs = smi.terms.docs(smi.reader.getDeletedDocs());
      int base = smi.base;
      int[] docMap = smi.getDocMap();

      while (true) {
        int startDoc = docs.next();
        if (startDoc == DocsEnum.NO_MORE_DOCS) {
          break;
        }
        if (Codec.DEBUG) {
          System.out.println("      merge read doc=" + startDoc);
        }

        df++;
        int doc;
        if (docMap != null) {
          // map around deletions
          doc = docMap[startDoc];
          assert doc != -1: "postings enum returned deleted docID " + startDoc + " freq=" + docs.freq() + " df=" + df;
        } else {
          doc = startDoc;
        }

        doc += base;                              // convert to merged space
        assert doc < mergedDocs: "doc=" + doc + " maxDoc=" + mergedDocs;

        final int freq = docs.freq();
        final PositionsConsumer posConsumer = docConsumer.addDoc(doc, freq);
        final PositionsEnum positions = docs.positions();

        // nocommit -- omitTF should be "private", and this
        // code (and FreqProxTermsWriter) should instead
        // check if posConsumer is null?
        
        if (!omitTermFreqAndPositions) {
          for (int j = 0; j < freq; j++) {
            final int position = positions.next();
            final int payloadLength = positions.getPayloadLength();
            if (payloadLength > 0) {
              if (payloadBuffer == null || payloadBuffer.length < payloadLength)
                payloadBuffer = new byte[payloadLength];
              positions.getPayload(payloadBuffer, 0);
            }
            posConsumer.addPosition(position, payloadBuffer, 0, payloadLength);
          }
          posConsumer.finishDoc();
        }
      }
    }
    termsConsumer.finishTerm(termBuffer.result, 0, df);

    return df;
  }

  private void mergeNorms() throws IOException {
    byte[] normBuffer = null;
    IndexOutput output = null;
    try {
      int numFieldInfos = fieldInfos.size();
      for (int i = 0; i < numFieldInfos; i++) {
        FieldInfo fi = fieldInfos.fieldInfo(i);
        if (fi.isIndexed && !fi.omitNorms) {
          if (output == null) { 
            output = directory.createOutput(segment + "." + IndexFileNames.NORMS_EXTENSION);
            output.writeBytes(NORMS_HEADER,NORMS_HEADER.length);
          }
          for ( IndexReader reader : readers) {
            int maxDoc = reader.maxDoc();
            if (normBuffer == null || normBuffer.length < maxDoc) {
              // the buffer is too small for the current segment
              normBuffer = new byte[maxDoc];
            }
            reader.norms(fi.name, normBuffer, 0);
            if (!reader.hasDeletions()) {
              //optimized case for segments without deleted docs
              output.writeBytes(normBuffer, maxDoc);
            } else {
              // this segment has deleted docs, so we have to
              // check for every doc if it is deleted or not
              for (int k = 0; k < maxDoc; k++) {
                if (!reader.isDeleted(k)) {
                  output.writeByte(normBuffer[k]);
                }
              }
            }
            checkAbort.work(maxDoc);
          }
        }
      }
    } finally {
      if (output != null) { 
        output.close();
      }
    }
  }

  static class CheckAbort {
    private double workCount;
    private MergePolicy.OneMerge merge;
    private Directory dir;
    public CheckAbort(MergePolicy.OneMerge merge, Directory dir) {
      this.merge = merge;
      this.dir = dir;
    }

    /**
     * Records the fact that roughly units amount of work
     * have been done since this method was last called.
     * When adding time-consuming code into SegmentMerger,
     * you should test different values for units to ensure
     * that the time in between calls to merge.checkAborted
     * is up to ~ 1 second.
     */
    public void work(double units) throws MergePolicy.MergeAbortedException {
      workCount += units;
      if (workCount >= 10000.0) {
        merge.checkAborted(dir);
        workCount = 0;
      }
    }
  }
  
}
