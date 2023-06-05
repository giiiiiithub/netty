/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import static io.netty.buffer.PoolThreadCache.log2;

/**
 * SizeClasses requires {@code pageShifts} to be defined prior to inclusion,
 * and it in turn defines:
 * <p>
 * LOG2_SIZE_CLASS_GROUP: Log of size class count for each size doubling.
 * LOG2_MAX_LOOKUP_SIZE: Log of max size class in the lookup table.
 * sizeClasses: Complete table of [index, log2Group, log2Delta, nDelta, isMultiPageSize,
 * isSubPage, log2DeltaLookup] tuples.
 * index: Size class index.
 * log2Group: Log of group base size (no deltas added).
 * log2Delta: Log of delta to previous size class.
 * nDelta: Delta multiplier.
 * isMultiPageSize: 'yes' if a multiple of the page size, 'no' otherwise.
 * isSubPage: 'yes' if a subpage size class, 'no' otherwise.
 * log2DeltaLookup: Same as log2Delta if a lookup table size class, 'no'
 * otherwise.
 * <p>
 * nSubpages: Number of subpages size classes.
 * nSizes: Number of size classes.
 * nPSizes: Number of size classes that are multiples of pageSize.
 * <p>
 * smallMaxSizeIdx: Maximum small size class index.
 * <p>
 * lookupMaxClass: Maximum size class included in lookup table.
 * log2NormalMinClass: Log of minimum normal size class.
 * <p>
 * The first size class and spacing are 1 << LOG2_QUANTUM.
 * Each group has 1 << LOG2_SIZE_CLASS_GROUP of size classes.
 * <p>
 * size = 1 << log2Group + nDelta * (1 << log2Delta)
 * <p>
 * The first size class has an unusual encoding, because the size has to be
 * split between group and delta*nDelta.
 * <p>
 * If pageShift = 13, sizeClasses looks like this:
 * <p>
 * (index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup)
 * <p>
 * ( 0,     4,        4,         0,       no,             yes,        4)
 * ( 1,     4,        4,         1,       no,             yes,        4)
 * ( 2,     4,        4,         2,       no,             yes,        4)
 * ( 3,     4,        4,         3,       no,             yes,        4)
 * <p>
 * ( 4,     6,        4,         1,       no,             yes,        4)
 * ( 5,     6,        4,         2,       no,             yes,        4)
 * ( 6,     6,        4,         3,       no,             yes,        4)
 * ( 7,     6,        4,         4,       no,             yes,        4)
 * <p>
 * ( 8,     7,        5,         1,       no,             yes,        5)
 * ( 9,     7,        5,         2,       no,             yes,        5)
 * ( 10,    7,        5,         3,       no,             yes,        5)
 * ( 11,    7,        5,         4,       no,             yes,        5)
 * ...
 * ...
 * ( 72,    23,       21,        1,       yes,            no,        no)
 * ( 73,    23,       21,        2,       yes,            no,        no)
 * ( 74,    23,       21,        3,       yes,            no,        no)
 * ( 75,    23,       21,        4,       yes,            no,        no)
 * <p>
 * ( 76,    24,       22,        1,       yes,            no,        no)
 */
abstract class SizeClasses implements SizeClassesMetric {

    static final int LOG2_QUANTUM = 4;

    private static final int LOG2_SIZE_CLASS_GROUP = 2;
    private static final int LOG2_MAX_LOOKUP_SIZE = 12;

    private static final int INDEX_IDX = 0;
    private static final int LOG2GROUP_IDX = 1;
    private static final int LOG2DELTA_IDX = 2;
    private static final int NDELTA_IDX = 3;
    private static final int PAGESIZE_IDX = 4;
    private static final int SUBPAGE_IDX = 5;
    private static final int LOG2_DELTA_LOOKUP_IDX = 6;

    private static final byte no = 0, yes = 1;

    protected final int pageSize;
    protected final int pageShifts;
    protected final int chunkSize;
    protected final int directMemoryCacheAlignment;

    final int nSizes;
    final int nSubpages;
    final int nPSizes;
    final int lookupMaxSize;
    final int smallMaxSizeIdx;

    private final int[] pageIdx2sizeTab;

    // lookup table for sizeIdx <= smallMaxSizeIdx
    private final int[] sizeIdx2sizeTab;

    // lookup table used for size <= lookupMaxClass
    // spacing is 1 << LOG2_QUANTUM, so the size of array is lookupMaxClass >> LOG2_QUANTUM
    private final int[] size2idxTab;

    private final short[][] sizeClasses;

    protected SizeClasses(int pageSize, int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
        int i = log2(chunkSize);
        //假设chunkSize=4194304, log2(chunkSize)=22, 也就是说最高可以左移22位，但是group是从16开始起步递增，所以要减去log2(16)=4;
        //
        //group实际从4开始的，但是没有经过5，直接到了6. 所以可以减去 6+1
        int groupNum = log2(chunkSize) - (LOG2_QUANTUM - LOG2_SIZE_CLASS_GROUP) + 1;// + 1;

        //generate size classes
        //[index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubPage, log2DeltaLookup]
        //group idx: group << 2   每个group有4项  group*4
        this.sizeClasses = new short[groupNum << LOG2_SIZE_CLASS_GROUP][7];

        int normalMaxSize = -1;
        int nSizes = 0;
        int size = 0;

        int log2Group = LOG2_QUANTUM;
        int log2Delta = LOG2_QUANTUM;
        int ndeltaLimit = 1 << LOG2_SIZE_CLASS_GROUP;

        //First small group, nDelta start at 0.   ndelta=[0,3], 1 _ _ _ _
        //first size class is 1 << LOG2_QUANTUM
        for (int nDelta = 0; nDelta < ndeltaLimit; nDelta++, nSizes++) {
            short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
            sizeClasses[nSizes] = sizeClass;
            size = sizeOf(sizeClass, directMemoryCacheAlignment);
            System.out.println();
        }

        log2Group += LOG2_SIZE_CLASS_GROUP;

        //All remaining groups, nDelta start at 1.  ndelta=[1,4]
        for (; size < chunkSize; log2Group++, log2Delta++) {
            for (int nDelta = 1; nDelta <= ndeltaLimit && size < chunkSize; nDelta++, nSizes++) {
                short[] sizeClass = newSizeClass(nSizes, log2Group, log2Delta, nDelta, pageShifts);
                sizeClasses[nSizes] = sizeClass;
                size = normalMaxSize = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }

        //chunkSize must be normalMaxSize
        assert chunkSize == normalMaxSize;

        int smallMaxSizeIdx = 0;
        int lookupMaxSize = 0;
        int nPSizes = 0;
        int nSubpages = 0;
        for (int idx = 0; idx < nSizes; idx++) {
            short[] sz = sizeClasses[idx];
            if (sz[PAGESIZE_IDX] == yes) {
                nPSizes++;
            }
            if (sz[SUBPAGE_IDX] == yes) {
                nSubpages++;
                smallMaxSizeIdx = idx;
            }
            if (sz[LOG2_DELTA_LOOKUP_IDX] != no) {
                lookupMaxSize = sizeOf(sz, directMemoryCacheAlignment);
            }
        }
        this.smallMaxSizeIdx = smallMaxSizeIdx;
        this.lookupMaxSize = lookupMaxSize;
        this.nPSizes = nPSizes;
        this.nSubpages = nSubpages;
        this.nSizes = nSizes;

        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        this.directMemoryCacheAlignment = directMemoryCacheAlignment;

        //size表， idx= sizeClasses idx
        sizeIdx2sizeTab = newIdx2SizeTab(sizeClasses, nSizes, directMemoryCacheAlignment);

        //整数页size表 (size是N倍的pageSize, N是大于等于0的整数）
        pageIdx2sizeTab = newPageIdx2sizeTab(sizeClasses, nSizes, nPSizes, directMemoryCacheAlignment);

        //小的size，可以直接查找该表得到size表的idx，再拿着这个idx去size表取大小
        //这张表每递增一个索引，相当于size+16，
        size2idxTab = newSize2idxTab(lookupMaxSize, sizeClasses);
        System.out.println();
    }

    //calculate size class
    private static short[] newSizeClass(int index, int log2Group, int log2Delta, int nDelta, int pageShifts) {
        short isMultiPageSize;
        int sizeX = 0;
        if (log2Delta >= pageShifts) {
            isMultiPageSize = yes;
        } else {
            int pageSize = 1 << pageShifts;
            int size = calculateSize(log2Group, nDelta, log2Delta);
            sizeX = size;
            isMultiPageSize = size == size / pageSize * pageSize? yes : no;
            System.out.println();
        }

        //0~3  || 1~4
        int log2Ndelta = nDelta == 0? 0 : log2(nDelta);

        //0, 0, 1<0? no
        //0, 1, 1<1? no
        //1, 2, 2<2? no
        //1, 3, 2<3? yes
        //2, 4, 4<4? no
        byte remove = 1 << log2Ndelta < nDelta? yes : no;

        //log2Delta和log2Group总是差2，  nDelta[1,4]
        //log2Ndelta在等于4的时候才是2。
        //log2Group=4，这里的计算不对。 log2Size=4,5,5,6

        //log2(sizeX);
        // 一旦log2Group=4, 就等于是算错了，但是log2Size这个变量被用在了两处，不影响。
        //第一处，影响了remove变量，但是remove变量的作用，需要log2Size=LOG2_MAX_LOOKUP_SIZE， 这个值的log2Group可比4要大
        //第二处，影响了isSubpage，isSubpage变量依赖了pageShifts， 而pageShifts的值来自于pageSize, pageSize默认值是4096， 其log2Group比4要大。

        //这个计算方式要比log2(size)快
        int log2Size = log2Delta + log2Ndelta == log2Group? log2Group + 1 : log2Group;

        // group>4的时候且ndelta等于1,2,3      log2group=4的时候，ndelta=1,0
        //LOG2_MAX_LOOKUP_SIZE=12, 所以，log2Group=4的时候，无所谓，用不上
        if (log2Size == log2Group) {
            remove = yes;
        }

        //是否子页？ size小于pageSize 为什么要加上 LOG2_SIZE_CLASS_GROUP？
        //比如pageShift=13(8192), 那么8192，16384，都是子页 ？  4096
        // pageShift:13
        short isSubpage = log2Size < pageShifts + 1? yes : no;

        //remove=no，意味着delta=4  LOG2_MAX_LOOKUP_SIZE=4096
        //表达式的意思是size不超过4096, 或者当size=4096时且Ndelta=4 (4096/2 时候group最后一个Ndelta)
        //group=11, delta=9, Ndelta=4 delta
        //remove=no, 说明log2Size是通过delta进位得来的，返回还是log2Delta
        int log2DeltaLookup = log2Size < LOG2_MAX_LOOKUP_SIZE ||
                              (log2Size == LOG2_MAX_LOOKUP_SIZE && remove == no)
                ? log2Delta : no;

        return new short[] {
                (short) index, (short) log2Group, (short) log2Delta,
                (short) nDelta, isMultiPageSize, isSubpage, (short) log2DeltaLookup
        };
    }

    private static int[] newIdx2SizeTab(short[][] sizeClasses, int nSizes, int directMemoryCacheAlignment) {
        int[] sizeIdx2sizeTab = new int[nSizes];

        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            sizeIdx2sizeTab[i] = sizeOf(sizeClass, directMemoryCacheAlignment);
        }
        return sizeIdx2sizeTab;
    }

    /**
     * 4,4,0 16
     * 4,4,1 16+16
     * 4,4,2 16+32
     * 4,4,3 16+48
     * <p>
     * 6,4,1  64+16
     * 6,4,2  64+32
     * 6,4,3  64+48
     * 6,4,4  64+64
     * <p>
     * 7,5,1 128+32
     * 7,5,2  128+64
     * ...
     *
     * @param log2Group
     * @param nDelta
     * @param log2Delta
     * @return
     */
    private static int calculateSize(int log2Group, int nDelta, int log2Delta) {
        return (1 << log2Group) + (nDelta << log2Delta);
    }

    private static int sizeOf(short[] sizeClass, int directMemoryCacheAlignment) {
        int log2Group = sizeClass[LOG2GROUP_IDX];
        int log2Delta = sizeClass[LOG2DELTA_IDX];
        int nDelta = sizeClass[NDELTA_IDX];

        int size = calculateSize(log2Group, nDelta, log2Delta);

        return alignSizeIfNeeded(size, directMemoryCacheAlignment);
    }

    private static int[] newPageIdx2sizeTab(short[][] sizeClasses, int nSizes, int nPSizes,
                                            int directMemoryCacheAlignment) {
        int[] pageIdx2sizeTab = new int[nPSizes];
        int pageIdx = 0;
        for (int i = 0; i < nSizes; i++) {
            short[] sizeClass = sizeClasses[i];
            if (sizeClass[PAGESIZE_IDX] == yes) {
                pageIdx2sizeTab[pageIdx++] = sizeOf(sizeClass, directMemoryCacheAlignment);
            }
        }
        return pageIdx2sizeTab;
    }

    private static int[] newSize2idxTab(int lookupMaxSize, short[][] sizeClasses) {
        //右移动4位，group个数
        int[] size2idxTab = new int[lookupMaxSize >> LOG2_QUANTUM];
        int idx = 0;
        int size = 0;

        //遍历size列表，直到size>lookupMaxSize

        //i代表了sizeId2SizeTab的索引， 也就是说，能根据i找到对应的size
        for (int i = 0; size <= lookupMaxSize; i++) {
            int log2Delta = sizeClasses[i][LOG2DELTA_IDX];
            // 6->2  7->3  8->4
            int times = 1 << log2Delta - LOG2_QUANTUM;
            //Ndelta---> delta决定了几次
            //idx: size - 1 >> LOG2_QUANTUM （减1只对第五位开始有0的起作用）
            //idx每增加1，相当于size增加16，但是sizes从6开始，每次
            while (size <= lookupMaxSize && times-- > 0) {
                size2idxTab[idx++] = i;
                size = idx + 1 << LOG2_QUANTUM;
            }
        }
        return size2idxTab;
    }

    @Override
    public int sizeIdx2size(int sizeIdx) {
        return sizeIdx2sizeTab[sizeIdx];
    }

    @Override
    public int sizeIdx2sizeCompute(int sizeIdx) {
        int group = sizeIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = sizeIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        int groupSize = group == 0? 0 :
                1 << LOG2_QUANTUM + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int lgDelta = shift + LOG2_QUANTUM - 1;
        int modSize = mod + 1 << lgDelta;

        return groupSize + modSize;
    }

    @Override
    public long pageIdx2size(int pageIdx) {
        return pageIdx2sizeTab[pageIdx];
    }

    @Override
    public long pageIdx2sizeCompute(int pageIdx) {
        int group = pageIdx >> LOG2_SIZE_CLASS_GROUP;
        int mod = pageIdx & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        long groupSize = group == 0? 0 :
                1L << pageShifts + LOG2_SIZE_CLASS_GROUP - 1 << group;

        int shift = group == 0? 1 : group;
        int log2Delta = shift + pageShifts - 1;
        int modSize = mod + 1 << log2Delta;

        return groupSize + modSize;
    }

    @Override
    public int size2SizeIdx(int size) {
        if (size == 0) {
            return 0;
        }
        if (size > chunkSize) {
            return nSizes;
        }

        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);

        if (size <= lookupMaxSize) {
            //size-1 / MIN_TINY
            int i = size - 1 >> LOG2_QUANTUM;
            return size2idxTab[size - 1 >> LOG2_QUANTUM];
        }

        int x = log2((size << 1) - 1);
        int shift = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM);

        //每个group有4个元素
        int groupN = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;

        int deltaN = size - 1 >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        return groupN + deltaN;
    }

    @Override
    public int pages2pageIdx(int pages) {
        return pages2pageIdxCompute(pages, false);
    }

    @Override
    public int pages2pageIdxFloor(int pages) {
        return pages2pageIdxCompute(pages, true);
    }

    private int pages2pageIdxCompute(int pages, boolean floor) {
        int pagesSize = pages << pageShifts;
        if (pagesSize > chunkSize) {
            return nPSizes;
        }

        int x = log2((pagesSize << 1) - 1);

        int shift = x < LOG2_SIZE_CLASS_GROUP + pageShifts
                ? 0 : x - (LOG2_SIZE_CLASS_GROUP + pageShifts);

        int group = shift << LOG2_SIZE_CLASS_GROUP;

        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1?
                pageShifts : x - LOG2_SIZE_CLASS_GROUP - 1;

        //group和delta之间的数
        int mod = pagesSize - 1 >> log2Delta & (1 << LOG2_SIZE_CLASS_GROUP) - 1;

        //group+
        int pageIdx = group + mod;

        if (floor && pageIdx2sizeTab[pageIdx] > pagesSize) {
            pageIdx--;
        }

        return pageIdx;
    }

    // Round size up to the nearest multiple of alignment.
    private static int alignSizeIfNeeded(int size, int directMemoryCacheAlignment) {
        if (directMemoryCacheAlignment <= 0) {
            return size;
        }
        int delta = size & directMemoryCacheAlignment - 1;
        return delta == 0? size : size + (directMemoryCacheAlignment - delta);
    }

    @Override
    public int normalizeSize(int size) {
        if (size == 0) {
            return sizeIdx2sizeTab[0];
        }
        size = alignSizeIfNeeded(size, directMemoryCacheAlignment);
        if (size <= lookupMaxSize) {
            int ret = sizeIdx2sizeTab[size2idxTab[size - 1 >> LOG2_QUANTUM]];
            assert ret == normalizeSizeCompute(size);
            return ret;
        }
        return normalizeSizeCompute(size);
    }

    private static int normalizeSizeCompute(int size) {
        int x = log2((size << 1) - 1);
        int log2Delta = x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1
                ? LOG2_QUANTUM : x - LOG2_SIZE_CLASS_GROUP - 1;
        int delta = 1 << log2Delta;
        int delta_mask = delta - 1;
        return size + delta_mask & ~delta_mask;
    }
}
