package h2o.samples;

import java.text.DecimalFormat;
import java.util.Random;

import water.*;

/**
 * Simplified version of H2O k-means algorithm for better readability.
 */
public class Part05_KMeans {
  public static void main(String[] args) throws Exception {
    Weaver.registerPackage("h2o.samples");
    water.Boot._init.boot(new String[] { "-mainClass", UserMain.class.getName() });
  }

  public static class UserMain {
    public static void main(String[] args) throws Exception {
      H2O.main(args);
      Key key = TestUtil.loadAndParseFile("test", "smalldata/gaussian/sdss174052.csv.gz");
      ValueArray va = DKV.get(key).get();

      // Which columns are used from the dataset, skip first in this case
      int[] cols = new int[va._cols.length - 1];
      for( int i = 0; i < cols.length; i++ )
        cols[i] = i + 1;

      // Create k clusters as arrays of doubles
      int k = 3;
      double[][] clusters = new double[k][va._cols.length];

      // Initialize first cluster to random row
      Random rand = new Random();
      long row = Math.max(0, (long) (rand.nextDouble() * va._numrows) - 1);
      AutoBuffer bits = va.getChunk(va.chknum(row));
      fill(clusters[0], va, bits, va.rowInChunk(va.chknum(row), row), cols);

      // Iterate over the dataset and show error for each step
      for( int i = 0; i < 100; i++ ) {
        KMeans task = new KMeans();
        task._mainKey = key;
        task._cols = cols;
        task._clusters = clusters;
        task.invoke(key);
        System.out.println("Error is " + task._error);

        for( int cluster = 0; cluster < clusters.length; cluster++ ) {
          if( task._counts[cluster] > 0 ) {
            for( int column = 0; column < cols.length; column++ ) {
              double value = task._sums[cluster][column] / task._counts[cluster];
              clusters[cluster][column] = value;
            }
          }
        }
      }

      DecimalFormat df = new DecimalFormat("#.00");

      System.out.println("Clusters:");
      for( int cluster = 0; cluster < clusters.length; cluster++ ) {
        for( int column = 0; column < cols.length; column++ )
          System.out.print(df.format(clusters[cluster][column]) + ", ");
        System.out.println("");
      }
    }
  }

  /**
   * For more complex tasks like this one, it is useful to marks fields that are provided by the
   * caller (IN), and fields generated by the task (OUT). IN fields can then be set to null when the
   * task is done using them, so that they do not get serialized back to the caller.
   */
  public static class KMeans extends MRTask {
    Key _mainKey;         // IN:  Dataset key
    int[] _cols;          // IN:  Columns in use
    double[][] _clusters; // IN:  Centroids/clusters

    double[][] _sums;     // OUT: Sum of features in each cluster
    int[] _counts;        // OUT: Count of rows in cluster
    double _error;        // OUT: Total sqr distance

    @Override public void map(Key key) {
      assert key.home();
      ValueArray va = DKV.get(_mainKey).get();
      AutoBuffer bits = va.getChunk(key);
      int rows = va.rpc(ValueArray.getChunkIndex(key));
      double[] values = new double[_cols.length];

      // Create result arrays
      _sums = new double[_clusters.length][_cols.length];
      _counts = new int[_clusters.length];
      ClusterDist cd = new ClusterDist();

      // Find closest cluster for each row
      for( int row = 0; row < rows; row++ ) {
        fill(values, va, bits, row, _cols);
        closest(_clusters, values, cd);
        int cluster = cd._cluster;
        _error += cd._dist;

        // Add values and increment counter for chosen cluster
        for( int column = 0; column < values.length; column++ )
          _sums[cluster][column] += values[column];
        _counts[cluster]++;
      }
      _mainKey = null;
      _cols = null;
      _clusters = null;
    }

    @Override public void reduce(DRemoteTask rt) {
      KMeans task = (KMeans) rt;
      if( _sums == null ) {
        _sums = task._sums;
        _counts = task._counts;
        _error = task._error;
      } else {
        for( int cluster = 0; cluster < _counts.length; cluster++ ) {
          for( int column = 0; column < _sums[0].length; column++ )
            _sums[cluster][column] += task._sums[cluster][column];
          _counts[cluster] += task._counts[cluster];
        }
        _error += task._error;
      }
    }
  }

  static void closest(double[][] clusters, double[] point, ClusterDist cd) {
    int min = -1;
    double minSqr = Double.MAX_VALUE;
    for( int cluster = 0; cluster < clusters.length; cluster++ ) {
      double sqr = 0;           // Sum of dimensional distances
      for( int column = 0; column < point.length; column++ ) {
        double delta = point[column] - clusters[cluster][column];
        sqr += delta * delta;
      }
      if( sqr < minSqr ) {
        min = cluster;
        minSqr = sqr;
      }
    }
    cd._cluster = min; // nearest cluster
    cd._dist = minSqr; // square-distance
  }

  static void fill(double[] values, ValueArray va, AutoBuffer bits, int row, int[] cols) {
    for( int i = 0; i < cols.length - 1; i++ ) {
      ValueArray.Column c = va._cols[cols[i]];
      double d = va.datad(bits, row, c);
      values[i] = d;
    }
  }

  static final class ClusterDist {
    int _cluster;
    double _dist;
  }
}
