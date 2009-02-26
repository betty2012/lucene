package org.apache.lucene.search.trie;

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

import java.util.Random;

import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.RangeQuery;
import org.apache.lucene.util.LuceneTestCase;

public class TestIntTrieRangeFilter extends LuceneTestCase
{
  // distance of entries
  private static final int distance = 6666;
  // shift the starting of the values to the left, to also have negative values:
  private static final int startOffset = - 1 << 15;
  // number of docs to generate for testing
  private static final int noDocs = 10000;
  
  private static final RAMDirectory directory;
  private static final IndexSearcher searcher;
  static {
    try {    
      directory = new RAMDirectory();
      IndexWriter writer = new IndexWriter(directory, new WhitespaceAnalyzer(),
      true, MaxFieldLength.UNLIMITED);
      
      // Add a series of noDocs docs with increasing int values
      for (int l=0; l<noDocs; l++) {
        Document doc=new Document();
        // add fields, that have a distance to test general functionality
        final int val=distance*l+startOffset;
        TrieUtils.addIndexedFields(doc,"field8", TrieUtils.trieCodeInt(val, 8));
        doc.add(new Field("field8", TrieUtils.intToPrefixCoded(val), Field.Store.YES, Field.Index.NO));
        TrieUtils.addIndexedFields(doc,"field4", TrieUtils.trieCodeInt(val, 4));
        doc.add(new Field("field4", TrieUtils.intToPrefixCoded(val), Field.Store.YES, Field.Index.NO));
        TrieUtils.addIndexedFields(doc,"field2", TrieUtils.trieCodeInt(val, 2));
        doc.add(new Field("field2", TrieUtils.intToPrefixCoded(val), Field.Store.YES, Field.Index.NO));
        // add ascending fields with a distance of 1, beginning at -noDocs/2 to test the correct splitting of range and inclusive/exclusive
        TrieUtils.addIndexedFields(doc,"ascfield8", TrieUtils.trieCodeInt(l-(noDocs/2), 8));
        TrieUtils.addIndexedFields(doc,"ascfield4", TrieUtils.trieCodeInt(l-(noDocs/2), 4));
        TrieUtils.addIndexedFields(doc,"ascfield2", TrieUtils.trieCodeInt(l-(noDocs/2), 2));
        writer.addDocument(doc);
      }
    
      writer.optimize();
      writer.close();
      searcher=new IndexSearcher(directory);
    } catch (Exception e) {
      throw new Error(e);
    }
  }
  
  private void testRange(int precisionStep) throws Exception {
    String field="field"+precisionStep;
    int count=3000;
    int lower=(distance*3/2)+startOffset, upper=lower + count*distance + (distance/3);
    IntTrieRangeFilter f=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), true, true);
    TopDocs topDocs = searcher.search(f.asQuery(), null, noDocs, Sort.INDEXORDER);
    System.out.println("Found "+f.getLastNumberOfTerms()+" distinct terms in range for field '"+field+"'.");
    ScoreDoc[] sd = topDocs.scoreDocs;
    assertNotNull(sd);
    assertEquals("Score doc count", count, sd.length );
    Document doc=searcher.doc(sd[0].doc);
    assertEquals("First doc", 2*distance+startOffset, TrieUtils.prefixCodedToInt(doc.get(field)) );
    doc=searcher.doc(sd[sd.length-1].doc);
    assertEquals("Last doc", (1+count)*distance+startOffset, TrieUtils.prefixCodedToInt(doc.get(field)) );
  }

  public void testRange_8bit() throws Exception {
    testRange(8);
  }
  
  public void testRange_4bit() throws Exception {
    testRange(4);
  }
  
  public void testRange_2bit() throws Exception {
    testRange(2);
  }
  
  private void testLeftOpenRange(int precisionStep) throws Exception {
    String field="field"+precisionStep;
    int count=3000;
    int upper=(count-1)*distance + (distance/3) + startOffset;
    IntTrieRangeFilter f=new IntTrieRangeFilter(field, precisionStep, null, new Integer(upper), true, true);
    TopDocs topDocs = searcher.search(f.asQuery(), null, noDocs, Sort.INDEXORDER);
    System.out.println("Found "+f.getLastNumberOfTerms()+" distinct terms in left open range for field '"+field+"'.");
    ScoreDoc[] sd = topDocs.scoreDocs;
    assertNotNull(sd);
    assertEquals("Score doc count", count, sd.length );
    Document doc=searcher.doc(sd[0].doc);
    assertEquals("First doc", startOffset, TrieUtils.prefixCodedToInt(doc.get(field)) );
    doc=searcher.doc(sd[sd.length-1].doc);
    assertEquals("Last doc", (count-1)*distance+startOffset, TrieUtils.prefixCodedToInt(doc.get(field)) );
  }
  
  public void testLeftOpenRange_8bit() throws Exception {
    testLeftOpenRange(8);
  }
  
  public void testLeftOpenRange_4bit() throws Exception {
    testLeftOpenRange(4);
  }
  
  public void testLeftOpenRange_2bit() throws Exception {
    testLeftOpenRange(2);
  }
  
  private void testRightOpenRange(int precisionStep) throws Exception {
    String field="field"+precisionStep;
    int count=3000;
    int lower=(count-1)*distance + (distance/3) +startOffset;
    IntTrieRangeFilter f=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), null, true, true);
    TopDocs topDocs = searcher.search(f.asQuery(), null, noDocs, Sort.INDEXORDER);
    System.out.println("Found "+f.getLastNumberOfTerms()+" distinct terms in right open range for field '"+field+"'.");
    ScoreDoc[] sd = topDocs.scoreDocs;
    assertNotNull(sd);
    assertEquals("Score doc count", noDocs-count, sd.length );
    Document doc=searcher.doc(sd[0].doc);
    assertEquals("First doc", count*distance+startOffset, TrieUtils.prefixCodedToInt(doc.get(field)) );
    doc=searcher.doc(sd[sd.length-1].doc);
    assertEquals("Last doc", (noDocs-1)*distance+startOffset, TrieUtils.prefixCodedToInt(doc.get(field)) );
  }
  
  public void testRightOpenRange_8bit() throws Exception {
    testRightOpenRange(8);
  }
  
  public void testRightOpenRange_4bit() throws Exception {
    testRightOpenRange(4);
  }
  
  public void testRightOpenRange_2bit() throws Exception {
    testRightOpenRange(2);
  }
  
  private void testRandomTrieAndClassicRangeQuery(int precisionStep) throws Exception {
    final Random rnd=newRandom();
    String field="field"+precisionStep;
    // 50 random tests, the tests may also return 0 results, if min>max, but this is ok
    for (int i=0; i<50; i++) {
      int lower=(int)(rnd.nextDouble()*noDocs*distance)+startOffset;
      int upper=(int)(rnd.nextDouble()*noDocs*distance)+startOffset;
      // test inclusive range
      Query tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), true, true).asQuery();
      RangeQuery cq=new RangeQuery(field, TrieUtils.intToPrefixCoded(lower), TrieUtils.intToPrefixCoded(upper), true, true);
      cq.setConstantScoreRewrite(true);
      TopDocs tTopDocs = searcher.search(tq, 1);
      TopDocs cTopDocs = searcher.search(cq, 1);
      assertEquals("Returned count for IntTrieRangeFilter and RangeQuery must be equal", cTopDocs.totalHits, tTopDocs.totalHits );
      // test exclusive range
      tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), false, false).asQuery();
      cq=new RangeQuery(field, TrieUtils.intToPrefixCoded(lower), TrieUtils.intToPrefixCoded(upper), false, false);
      cq.setConstantScoreRewrite(true);
      tTopDocs = searcher.search(tq, 1);
      cTopDocs = searcher.search(cq, 1);
      assertEquals("Returned count for IntTrieRangeFilter and RangeQuery must be equal", cTopDocs.totalHits, tTopDocs.totalHits );
      // test left exclusive range
      tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), false, true).asQuery();
      cq=new RangeQuery(field, TrieUtils.intToPrefixCoded(lower), TrieUtils.intToPrefixCoded(upper), false, true);
      cq.setConstantScoreRewrite(true);
      tTopDocs = searcher.search(tq, 1);
      cTopDocs = searcher.search(cq, 1);
      assertEquals("Returned count for IntTrieRangeFilter and RangeQuery must be equal", cTopDocs.totalHits, tTopDocs.totalHits );
      // test right exclusive range
      tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), true, false).asQuery();
      cq=new RangeQuery(field, TrieUtils.intToPrefixCoded(lower), TrieUtils.intToPrefixCoded(upper), true, false);
      cq.setConstantScoreRewrite(true);
      tTopDocs = searcher.search(tq, 1);
      cTopDocs = searcher.search(cq, 1);
      assertEquals("Returned count for IntTrieRangeFilter and RangeQuery must be equal", cTopDocs.totalHits, tTopDocs.totalHits );
    }
  }
  
  public void testRandomTrieAndClassicRangeQuery_8bit() throws Exception {
    testRandomTrieAndClassicRangeQuery(8);
  }
  
  public void testRandomTrieAndClassicRangeQuery_4bit() throws Exception {
    testRandomTrieAndClassicRangeQuery(4);
  }
  
  public void testRandomTrieAndClassicRangeQuery_2bit() throws Exception {
    testRandomTrieAndClassicRangeQuery(2);
  }
  
  private void testRangeSplit(int precisionStep) throws Exception {
    final Random rnd=newRandom();
    String field="ascfield"+precisionStep;
    // 50 random tests
    for (int i=0; i<50; i++) {
      int lower=(int)(rnd.nextDouble()*noDocs - noDocs/2);
      int upper=(int)(rnd.nextDouble()*noDocs - noDocs/2);
      if (lower>upper) {
        int a=lower; lower=upper; upper=a;
      }
      // test inclusive range
      Query tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), true, true).asQuery();
      TopDocs tTopDocs = searcher.search(tq, 1);
      assertEquals("Returned count of range query must be equal to inclusive range length", upper-lower+1, tTopDocs.totalHits );
      // test exclusive range
      tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), false, false).asQuery();
      tTopDocs = searcher.search(tq, 1);
      assertEquals("Returned count of range query must be equal to exclusive range length", Math.max(upper-lower-1, 0), tTopDocs.totalHits );
      // test left exclusive range
      tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), false, true).asQuery();
      tTopDocs = searcher.search(tq, 1);
      assertEquals("Returned count of range query must be equal to half exclusive range length", upper-lower, tTopDocs.totalHits );
      // test right exclusive range
      tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), true, false).asQuery();
      tTopDocs = searcher.search(tq, 1);
      assertEquals("Returned count of range query must be equal to half exclusive range length", upper-lower, tTopDocs.totalHits );
    }
  }

  public void testRangeSplit_8bit() throws Exception {
    testRangeSplit(8);
  }
  
  public void testRangeSplit_4bit() throws Exception {
    testRangeSplit(4);
  }
  
  public void testRangeSplit_2bit() throws Exception {
    testRangeSplit(2);
  }
  
  private void testSorting(int precisionStep) throws Exception {
    final Random rnd=newRandom();
    String field="field"+precisionStep;
    // 10 random tests, the index order is ascending,
    // so using a reverse sort field should retun descending documents
    for (int i=0; i<10; i++) {
      int lower=(int)(rnd.nextDouble()*noDocs*distance)+startOffset;
      int upper=(int)(rnd.nextDouble()*noDocs*distance)+startOffset;
      if (lower>upper) {
        int a=lower; lower=upper; upper=a;
      }
      Query tq=new IntTrieRangeFilter(field, precisionStep, new Integer(lower), new Integer(upper), true, true).asQuery();
      TopDocs topDocs = searcher.search(tq, null, noDocs, new Sort(TrieUtils.getIntSortField(field, true)));
      if (topDocs.totalHits==0) continue;
      ScoreDoc[] sd = topDocs.scoreDocs;
      assertNotNull(sd);
      int last=TrieUtils.prefixCodedToInt(searcher.doc(sd[0].doc).get(field));
      for (int j=1; j<sd.length; j++) {
        int act=TrieUtils.prefixCodedToInt(searcher.doc(sd[j].doc).get(field));
        assertTrue("Docs should be sorted backwards", last>act );
        last=act;
      }
    }
  }

  public void testSorting_8bit() throws Exception {
    testSorting(8);
  }
  
  public void testSorting_4bit() throws Exception {
    testSorting(4);
  }
  
  public void testSorting_2bit() throws Exception {
    testSorting(2);
  }
  
}