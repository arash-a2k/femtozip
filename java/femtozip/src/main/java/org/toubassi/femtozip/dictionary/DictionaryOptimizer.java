/**
 *   Copyright 2011 Garrick Toubassi
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.toubassi.femtozip.dictionary;

import java.io.*;
import java.nio.ByteBuffer;

import org.toubassi.femtozip.DocumentList;
import org.toubassi.femtozip.util.StreamUtil;

import java.nio.charset.StandardCharsets;
import java.util.*;


public class DictionaryOptimizer {

    private SubstringArray substrings;
    private byte[] bytes;
    private int[] suffixArray;
    private int[] lcp;
    private int[] starts;

    public DictionaryOptimizer(DocumentList documents) throws IOException {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        starts = new int[documents.size()];

        for (int i = 0, count = documents.size(); i < count; i++) {
            ByteBuffer document = documents.getBB(i);

            while(document.hasRemaining()){
                bytesOut.write(document.get());
            }
            starts[i] = bytesOut.size();
        }

        bytes = bytesOut.toByteArray();
    }

    public static ByteBuffer getOptimizedDictionary(DocumentList documents, int desiredLength) throws IOException {
        DictionaryOptimizer dicOpt = new DictionaryOptimizer(documents);
        return dicOpt.optimize(desiredLength);
    }
    
    public ByteBuffer getOptimizedDictionary(int desiredLength) throws IOException {
        return optimize(desiredLength);
    }

    public static ByteBuffer readDictionary(DataInputStream in) throws IOException {
        int dictionaryLength = in.readInt();

        if (dictionaryLength == -1) {
            return ByteBuffer.allocate(0);
        }
        else {
            byte[] dictionary = new byte[dictionaryLength];
            int totalRead = StreamUtil.readBytes(in, dictionary, dictionaryLength);
            if (totalRead != dictionaryLength) {
                throw new IOException("Bad model in stream.  Could not read dictionary of length " + dictionaryLength);
            }

            return ByteBuffer.wrap(dictionary);
        }
    }

    public ByteBuffer optimize(int desiredLength) { //TODO: subsequent calls should only pack
        if(bytes.length > 0) {
            suffixArray = SuffixArray.computeSuffixArray(bytes);
            lcp = SuffixArray.computeLCP(bytes, suffixArray);

            computeSubstrings();
            return pack(desiredLength);
        }
        else {
            return ByteBuffer.allocate(0);
        }
    }

    protected void computeSubstrings() {
        SubstringArray activeSubstrings = new SubstringArray(128);
        Set<Integer> uniqueDocIds = new HashSet<>();

        int recentDocStartsBase = 0;
        ArrayList<int[] > recentDocStarts = new ArrayList<>();
        recentDocStarts.add(docStartForIndex(0));

        substrings = new SubstringArray(1024);
        int n = lcp.length;

        int lastLCP = lcp[0];
        for (int i = 1; i <= n; i++) {
            // Note we need to process currently existing runs, so we do that by acting like we hit an LCP of 0 at the end.
            // That is why the we loop i <= n vs i < n.  Otherwise runs that exist at the end of the suffixarray/lcp will
            // never be "cashed in" and counted in the substrings.  DictionaryOptimizerTest has a unit test for this.
            //int currentLCP = i == n ? 0 : lcp[i];

            int currentLCP;
            if (i == n) {
                currentLCP = 0;
            }
            else {
                currentLCP = lcp[i];
                recentDocStarts.add(docStartForIndex(i));
            }

            if (currentLCP > lastLCP) {
                // The order here is important so we can optimize adding redundant strings below.
                for (int j = lastLCP + 1; j <= currentLCP; j++) {
                    activeSubstrings.add(i, j, 0);
                }
            }
            else if (currentLCP < lastLCP) {
                int lastActiveIndex = -1, lastActiveLength = -1, lastActiveCount = -1;
                for (int j = activeSubstrings.size() - 1; j >= 0; j--) {
                    if (activeSubstrings.length(j) > currentLCP) {
                        int activeCount = i - activeSubstrings.index(j) + 1;
                        int activeLength = activeSubstrings.length(j);
                        int activeIndex = activeSubstrings.index(j);

                        // Ok we have a string which occurs activeCount times.  The true measure of its
                        // value is how many unique documents it occurs in, because occurring 1000 times in the same
                        // document isn't valuable because once it occurs once, subsequent occurrences will reference
                        // a previous occurring instance in the document.  So for 2 documents: "garrick garrick garrick toubassi",
                        // "toubassi", the string toubassi is far more valuable in a shared dictionary.  So find out
                        // how many unique documents this string occurs in.  We do this by taking the start position of
                        // each occurrence, and then map that back to the document using the "starts" array, and uniquing.
                        for (int k = activeSubstrings.index(j) - 1; k < i; k++) {

                            int byteIndex = suffixArray[k];
                            int[] docRange = recentDocStarts.get(k - recentDocStartsBase);

                            // While we are at it lets make sure this is a string that actually exists in a single
                            // document, vs spanning two concatenated documents.  The idea is that for documents
                            // "http://espn.com", "http://google.com", "http://yahoo.com", we don't want to consider
                            // ".comhttp://" to be a legal string.  So make sure the length of this string doesn't
                            // cross a document boundary for this particular occurrence.
                            if (activeLength <= docRange[1] - (byteIndex - docRange[0])) {
                                uniqueDocIds.add(docRange[0]);
                            }
                        }

                        int scoreCount = uniqueDocIds.size();

                        // You might think that its better to just clear uniqueDocIds,
                        // but actually this set can getBB very large, and calling clear
                        // loops over all entries in the internal array and sets them to
                        // null, doesn't even use Arrays.fill, and unfortunately doesn't
                        // short circuit out if size is already 0 (which is a common case
                        // in this code).  Doing: if (uniqueDocIds.size() > 0) uniqueDocIds.clear();
                        // is still not as fast as just tossing the set overboard.
                        uniqueDocIds = new HashSet<>();
                        activeSubstrings.remove(j);

                        if (scoreCount == 0) {
                            continue;
                        }

                        // Don't add redundant strings.  If we just  added ABC, don't add AB if it has the same count.  This cuts down the size of substrings
                        // from growing very large.
                        if (!(lastActiveIndex != -1 && lastActiveIndex == activeIndex && lastActiveCount == activeCount && lastActiveLength > activeLength)) {

                            // Empirically determined that we need 4 chars for it to be worthwhile.  Note gzip takes 3, so cause for skepticism at going with 4.
                            if (activeLength > 3) {
                                substrings.add(activeIndex, activeLength, scoreCount);
                            }
                        }
                        lastActiveIndex = activeIndex;
                        lastActiveLength = activeLength;
                        lastActiveCount = activeCount;
                    }
                }
            }
            lastLCP = currentLCP;


            if (activeSubstrings.size() == 0 && recentDocStarts.size() > 1) {
                int[] last = recentDocStarts.get(recentDocStarts.size() - 1);
                recentDocStartsBase += recentDocStarts.size() - 1;
                recentDocStarts = new ArrayList<>();
                recentDocStarts.add(last);
            }
        }
        substrings.sort();
    }

    protected ByteBuffer pack(int desiredLength) {

        // First, filter out the substrings to remove overlap since
        // many of the substrings are themselves substrings of each other (e.g. 'http://', 'ttp://').
        SubstringArray pruned = getSubstringArrayPruned(desiredLength);

        // Now pack the substrings end to end, taking advantage of potential prefix/suffix overlap
        // (e.g. if we are packing "toubassi" and "silence", pack it as
        // "toubassilence" vs "toubassisilence")
        byte[] packed = new byte[desiredLength];
        int pi = desiredLength;

        int i, count;
        for (i = 0, count = pruned.size(); i < count && pi > 0; i++) {
            int length = pruned.length(i);
            if (pi - length < 0) {
                length = pi;
            }
            pi -= prepend(bytes, suffixArray[pruned.index(i)], packed, pi, length);
        }

        if (pi > 0) {
            packed = Arrays.copyOfRange(packed, pi, packed.length);
        }

        return ByteBuffer.wrap(packed);
    }


    public Map<ByteBuffer, Integer> calcSubstringScores(int desiredLength) {

        Map<ByteBuffer, Integer> dictSubScores = new LinkedHashMap<>();
        SubstringArray pruned = getSubstringArrayPruned(desiredLength);

        byte[] packed = new byte[desiredLength];
        int pi = desiredLength;

        int i, count;
        for (i = 0, count = pruned.size(); i < count && pi > 0; i++) {
            int length = pruned.length(i);
            if (pi - length < 0) {
                length = pi;
            }
            pi -= prepend(bytes, suffixArray[pruned.index(i)], packed, pi, length);

            //storing substring and scores of the dictionary
            byte[] subString = Arrays.copyOfRange(bytes, suffixArray[pruned.index(i)], suffixArray[pruned.index(i)] + length);
            dictSubScores.put(ByteBuffer.wrap(subString), pruned.score(i));
        }

        return dictSubScores;
    }

    private SubstringArray getSubstringArrayPruned(int desiredLength) {
        SubstringArray pruned = new SubstringArray(1024);
        int size = 0;

        for (int i = substrings.size() - 1; i >= 0; i--) {
            boolean alreadyCovered = false;
            for (int j = 0, c = pruned.size(); j < c; j++) {
                if (pruned.indexOf(j, substrings, i, bytes, suffixArray) != -1) {

                    alreadyCovered = true;
                    break;
                }
            }

            if (alreadyCovered) {
                continue;
            }

            for (int j = pruned.size() - 1; j >= 0; j--) {
                if (substrings.indexOf(i, pruned, j, bytes, suffixArray) != -1) {
                    size -= pruned.length(j);
                    pruned.remove(j);
                }
            }
            pruned.setScore(pruned.size(), substrings.index(i), substrings.length(i), substrings.score(i));


            size += substrings.length(i);


            // We calculate 2x because when we lay the strings out end to end we will merge common prefix/suffixes
            if (size >= 2*desiredLength) {
                break;
            }

        }
        return pruned;
    }

    /***
     * Returns the offset into the byte buffer representing the
     * start of the document which contains the specified byte
     * (as an offset into the byte buffer).  So for example
     * docStartForIndex(0) always returns 0, and
     * docStartForIndex(15) will return 10 if the first doc is
     * 10 bytes and the second doc is at least 5.
     * @param index
     * @return
     */
    private int[] docStartForIndex(int index) {
        int byteIndex = suffixArray[index];
        int docStart = lower_bound(starts, starts.length, byteIndex);
        //int docStart = Arrays.binarySearch(starts, byteIndex);

        if (docStart == starts.length || starts[docStart] != byteIndex) {
            docStart--;
        }
        int nextDoc;
        if (docStart == (starts.length - 1)) {
            nextDoc = bytes.length;
        }
        else {
            nextDoc = starts[docStart +1];
        }
        return new int[]{docStart, nextDoc - (docStart)};
    }

    private int lower_bound(int a[], int n, int x) {
        int low = 0;
        int high = n;
        while (low < high) {
            int middle = (low + high) / 2;
            if (x <= a[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private int prepend(byte[] from, int fromIndex, byte[] to, int toIndex, int length) {
        int l;
        // See if we have a common suffix/prefix between the string being merged in, and the current strings in the front
        // of the destination.  For example if we pack " the " and then pack " and ", we should end up with " and the ", not " and  the ".
        for (l = Math.min(length - 1, to.length - toIndex); l > 0; l--) {
            if (byteRangeEquals(from, fromIndex + length - l, to, toIndex, l)) {
                break;
            }
        }
        int copyLenght = length -l;
        int toStartIndex = toIndex - length + l;

        System.arraycopy(from, fromIndex, to, toStartIndex, copyLenght);
        return length - l;
    }

    private static boolean byteRangeEquals(byte[] bytes1, int index1, byte[] bytes2, int index2, int length) {
        for (;length > 0; length--, index1++, index2++) {
            if (bytes1[index1] != bytes2[index2]) {
                return false;
            }
        }
        return true;
    }

    public int getSubstringCount() {
        return substrings.size();
    }

    public int getSubstringScore(int i) {
        return substrings.score(i);
    }

    public byte[] getSubstringBytes(int i) {
        int index = suffixArray[substrings.index(i)];
        int length = substrings.length(i);
        return Arrays.copyOfRange(bytes, index, index + length);
    }

    /**
     * For debugging
     */
    public void dumpSuffixArray(PrintStream out) {
        for (int i = 0; i < suffixArray.length; i++) {
            out.print(suffixArray[i] + "\t");
            out.print(lcp[i] + "\t");
            out.write(bytes, suffixArray[i], Math.min(40, bytes.length - suffixArray[i]));
            out.println();
        }
    }

    /**
     * For debugging
     */
    public void dumpSubstrings(PrintStream out) {
        if (substrings != null) {
            for (int j = substrings.size() - 1; j >= 0; j--) {
                out.print(substrings.score(j) + "\t");
                out.write(bytes, suffixArray[substrings.index(j)], Math.min(40, substrings.length(j)));
                out.println();
            }
        }
    }
}
