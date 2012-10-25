/*
 * Copyright (c) 2010 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.collections;

import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: ebanks
 * Date: July 1, 2012
 */

public class NestedIntegerArray<T> {

    protected final Object[] data;

    protected final int numDimensions;
    protected final int[] dimensions;

    public NestedIntegerArray(final int... dimensions) {
        numDimensions = dimensions.length;
        if ( numDimensions == 0 )
            throw new ReviewedStingException("There must be at least one dimension to an NestedIntegerArray");
        this.dimensions = dimensions.clone();

        data = new Object[dimensions[0]];
        prepopulateArray(data, 0);
    }

    /**
     * Recursively allocate the entire tree of arrays in all its dimensions.
     *
     * Doing this upfront uses more memory initially, but saves time over the course of the run
     * and (crucially) avoids having to make threads wait while traversing the tree to check
     * whether branches exist or not.
     *
     * @param subarray current node in the tree
     * @param dimension current level in the tree
     */
    private void prepopulateArray( Object[] subarray, int dimension ) {
        if ( dimension >= numDimensions - 1 ) {
            return;
        }

        for ( int i = 0; i < subarray.length; i++ ) {
            subarray[i] = new Object[dimensions[dimension + 1]];
            prepopulateArray((Object[])subarray[i], dimension + 1);
        }
    }

    public T get(final int... keys) {
        final int numNestedDimensions = numDimensions - 1;
        Object[] myData = data;

        for( int i = 0; i < numNestedDimensions; i++ ) {
            if ( keys[i] >= dimensions[i] )
                return null;

            myData = (Object[])myData[keys[i]];  // interior nodes in the tree will never be null, so we can safely traverse
                                                 // down to the leaves
        }

        return (T)myData[keys[numNestedDimensions]];
    }

    /**
     * Insert a value at the position specified by the given keys.
     *
     * This method is THREAD-SAFE despite not being synchronized, however the caller MUST
     * check the return value to see if the put succeeded. This method RETURNS FALSE if
     * the value could not be inserted because there already was a value present
     * at the specified location. In this case the caller should do a get() to get
     * the already-existing value and (potentially) update it.
     *
     * @param value value to insert
     * @param keys keys specifying the location of the value in the tree
     * @return true if the value was inserted, false if it could not be inserted because there was already
     *         a value at the specified position
     */
    public boolean put(final T value, final int... keys) { // WARNING! value comes before the keys!
        if ( keys.length != numDimensions )
            throw new ReviewedStingException("Exactly " + numDimensions + " keys should be passed to this NestedIntegerArray but " + keys.length + " were provided");

        final int numNestedDimensions = numDimensions - 1;
        Object[] myData = data;
        for ( int i = 0; i < numNestedDimensions; i++ ) {
            if ( keys[i] >= dimensions[i] )
                throw new ReviewedStingException("Key " + keys[i] + " is too large for dimension " + i + " (max is " + (dimensions[i]-1) + ")");

            myData = (Object[])myData[keys[i]];  // interior nodes in the tree will never be null, so we can safely traverse
                                                 // down to the leaves
        }

        synchronized ( myData ) {   // lock the bottom row while we examine and (potentially) update it

            // Insert the new value only if there still isn't any existing value in this position
            if ( myData[keys[numNestedDimensions]] == null ) {
                myData[keys[numNestedDimensions]] = value;
            }
            else {
                // Already have a value for this leaf (perhaps another thread came along and inserted one
                // while we traversed the tree), so return false to notify the caller that we didn't put
                // the item
                return false;
            }
        }

        return true;
    }

    public List<T> getAllValues() {
        final List<T> result = new ArrayList<T>();
        fillAllValues(data, result);
        return result;
    }

    private void fillAllValues(final Object[] array, final List<T> result) {
        for ( Object value : array ) {
            if ( value == null )
                continue;
            if ( value instanceof Object[] )
                fillAllValues((Object[])value, result);
            else
                result.add((T)value);
        }
    }

    public static class Leaf {
        public final int[] keys;
        public final Object value;

        public Leaf(final int[] keys, final Object value) {
            this.keys = keys;
            this.value = value;
        }
    }

    public List<Leaf> getAllLeaves() {
        final List<Leaf> result = new ArrayList<Leaf>();
        fillAllLeaves(data, new int[0], result);
        return result;
    }

    private void fillAllLeaves(final Object[] array, final int[] path, final List<Leaf> result) {
        for ( int key = 0; key < array.length; key++ ) {
            final Object value = array[key];
            if ( value == null )
                continue;
            final int[] newPath = appendToPath(path, key);
            if ( value instanceof Object[] ) {
                fillAllLeaves((Object[]) value, newPath, result);
            } else {
                result.add(new Leaf(newPath, value));
            }
        }
    }

    private int[] appendToPath(final int[] path, final int newKey) {
        final int[] newPath = new int[path.length + 1];
        for ( int i = 0; i < path.length; i++ )
            newPath[i] = path[i];
        newPath[path.length] = newKey;
        return newPath;
    }
}
