package com.orange.sparkproject.spark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import scala.Tuple2;

import com.orange.sparkproject.constant.Constants;
import com.orange.sparkproject.dao.AndroidPageSplitConvertRateDAO;
import com.orange.sparkproject.dao.factory.DAOFactory;
import com.orange.sparkproject.domain.PageSplitConvertRate;
import com.orange.sparkproject.util.DateUtils;
import com.orange.sparkproject.util.NumberUtils;

@SuppressWarnings("all")
public class PageJumpConvertRateForAndroid7Day {

	public void PageJumpConvertRateForAndroid7Day() {
		// 1.构建sparksession
		SparkSession spark = getSparkSession();
		// 2.获取任务参数
		JavaRDD<Row> actionRDD = generateRDD(spark);
		// 3.获取<userid，pageFlow>的格式
		JavaPairRDD<String, Row> generatePageSplitPV = getgenerateRDDTuple(actionRDD);
		generatePageSplitPV.cache();
		JavaPairRDD<String, Iterable<Row>> sessionid2actionsRDD = generatePageSplitPV
				.groupByKey();
		// 4.核心计算
		JavaPairRDD<String, Integer> generateAndMatchPageSplit = generateAndMatchPageSplit(
				spark, sessionid2actionsRDD);
		// 5.根据split聚合,得到splits + pvs
		JavaPairRDD<String, Integer> splitAndPV = reducePageSplit(generateAndMatchPageSplit);
		splitAndPV.cache();
		// 6.split +pv Map
		Map<String, Integer> splitPvMAP = getSplitPvMAP(splitAndPV);
		// 7.计算首页pv
		JavaRDD<Integer> firstPagePV = getFirstPagePV(splitAndPV);
		String first2PV = firstPagePV.take(2).toString();
		String firstPV = first2PV.split(",")[1];
		Long firstPage = Long
				.valueOf(firstPV.substring(1, firstPV.length() - 1));
		// 8.计算convertRateMap
		Map<String, Double> splitPvRateMap = getSplitPvRateMap(splitPvMAP,
				firstPage);
		// 9.构造一个<——>1,pv>的Map
		JavaPairRDD<String, Integer> getfenmuMap = getfenmuMap(splitAndPV);
		Map<String, Integer> fenmuMapPV = getfenmuMapReduceByKey(getfenmuMap);
		// 10.计算相对转化率Map
		Map<String, Double> splitRate2Map = getSplitRate2Map(splitPvMAP,
				fenmuMapPV);
		// 11..插入mysql
		persistConvertRate(splitPvRateMap, splitPvMAP, splitRate2Map,firstPV);

		spark.stop();
	}

	
	
	/**
	 * 求Map<K,V>中Value(值)的最大值
	 * 
	 * @param map
	 * @return
	 */
	private static Object getMaxValue(Map<String, Integer> splitPvMAP) {
		if (splitPvMAP == null)
			return null;
		Collection<Integer> c = splitPvMAP.values();
		Object[] obj = c.toArray();
		Arrays.sort(obj);
		return obj[obj.length - 2];
	}

	/**
	 * 配置spark作业环境
	 * 
	 * @return
	 */
	private static SparkSession getSparkSession() {
		String warehouseLocation = "/code/VersionTest/spark-warehouse";
		SparkSession spark = SparkSession.builder()
				.appName("PageJumpConvertRateForAndroid")
				.master(Constants.SPARK_LOCAL)
				.config("spark.sql.warehouse.dir", warehouseLocation)
				// .enableHiveSupport()
				.getOrCreate();
		return spark;
	}

	/**
	 * 获取数据，创建收个RDD<Row>
	 * 
	 * @param spark
	 * @return
	 */
	private static JavaRDD<Row> generateRDD(SparkSession spark) {
		JavaRDD<String> pageRDD = spark
				.sparkContext()
				.textFile(
						Constants.HDFS_URL_ANDROID_7D,
						3).toJavaRDD();

		String schemaString = "userid pagename functionname logintime logouttime";

		// Generate the schema based on the string of schema
		List<StructField> fields = new ArrayList<StructField>();
		for (String fieldName : schemaString.split(" ")) {
			StructField field = DataTypes.createStructField(fieldName,
					DataTypes.StringType, true);
			fields.add(field);
		}

		StructType schema = DataTypes.createStructType(fields);

		JavaRDD<Row> rowRDD = pageRDD.map(new Function<String, Row>() {

			@Override
			public Row call(String row) throws Exception {
				String[] attributes = row.split(",");
				if (attributes.length == 5) {

					String[] attr = attributes;
					return RowFactory.create(attr[0], attr[1], attr[2],
							attr[3], attr[4]);
				} else {
					return RowFactory.create(null, null, null, null, null);
				}
			}
		});
		// Apply the schema to the RDD
		Dataset<Row> peopleDataFrame = spark.createDataFrame(rowRDD, schema);

		// Creates a temporary view using the DataFrame
		peopleDataFrame.createOrReplaceTempView("pagedata");
		JavaRDD<Row> rowRDDs = spark
				.sql("select userid,pagename,logintime from pagedata where logintime is not null sort by logintime")
				.javaRDD();
		return rowRDDs;
	}

	/**
	 * 创建一个会话<userid,1,2,3,6,7,8>
	 * 
	 * @param actionRDD
	 * @return
	 * @return
	 */
	private static JavaPairRDD<String, String> getSessionRDD(
			JavaRDD<Row> actionRDD) {

		return actionRDD.mapToPair(new PairFunction<Row, String, String>() {

			@Override
			public Tuple2<String, String> call(Row row) throws Exception {
				String userid = String.valueOf(row.get(0));
				String pageid = String.valueOf(row.get(1));
				String logintime = String.valueOf(row.get(2));
				String page_time = pageid + " " + logintime;
				return new Tuple2<String, String>(userid, page_time);
			}
		});
	}

	/**
	 * 生成userid+pageflow
	 * 
	 * @param userPageRDDS
	 */
	private static JavaPairRDD<String, String> generatePageSplit(
			JavaPairRDD<String, Iterable<String>> userPageRDDS) {
		return userPageRDDS
				.mapToPair(new PairFunction<Tuple2<String, Iterable<String>>, String, String>() {

					@Override
					public Tuple2<String, String> call(
							Tuple2<String, Iterable<String>> row)
							throws Exception {

						String split = row._1();
						String[] page_time = row._2().toString().split(" ");
						String pageFlow = page_time[0];

						return new Tuple2<String, String>(split, pageFlow);
					}
				});
	}

	/**
	 * 获得userid,row的形式
	 * 
	 * @param actionRDD
	 * @return
	 */
	private static JavaPairRDD<String, Row> getgenerateRDDTuple(
			JavaRDD<Row> actionRDD) {
		return actionRDD.mapToPair(new PairFunction<Row, String, Row>() {

			@Override
			public Tuple2<String, Row> call(Row row) throws Exception {
				String userid = row.getString(0);
				return new Tuple2<String, Row>(userid, row);
			}
		});

	}

	/**
	 * 核心计算
	 * 
	 * @param actionRDD
	 * @return
	 */
	private static JavaPairRDD<String, Integer> generateAndMatchPageSplit(
			SparkSession spark,
			JavaPairRDD<String, Iterable<Row>> sessionid2actionsRDD) {

		return sessionid2actionsRDD
				.flatMapToPair(new PairFlatMapFunction<Tuple2<String, Iterable<Row>>, String, Integer>() {

					@Override
					public Iterator<Tuple2<String, Integer>> call(
							Tuple2<String, Iterable<Row>> tuple)
							throws Exception {

						ArrayList<Tuple2<String, Integer>> list = new ArrayList<Tuple2<String, Integer>>();
						// tuple._2.iterator和ArrayList<Row>相结合，用while(遍历iterator)
						Iterator<Row> iterator = tuple._2.iterator();
						ArrayList<Row> rows = new ArrayList<Row>();
						while (iterator.hasNext()) {
							rows.add(iterator.next());
						}

						System.setProperty(
								"java.util.Arrays.useLegacyMergeSort", "true");
						rows.sort(new Comparator<Row>() {
							@Override
							public int compare(Row r1, Row r2) {
								return (int) r1.getString(2).compareTo(
										r2.getString(2));
							}
						});
						String lastPageId = null;

						// 页面切片的生成
						for (Row row : rows) {
							String pageid = row.getString(1);

							if (lastPageId == null) {
								lastPageId = pageid;
								continue;
							}
							// 生成一个页面切片
							// 3,5,2,1,8,9
							// lastPageId=3
							// 5，切片，3_5
							String pageSplit = lastPageId + "——>" + pageid;

							// 对这个切片判断一下，是否在用户指定的页面流中
							for (int i = 1; i < row.length(); i++) {
								// 比如说，用户指定的页面流是3,2,5,8,1
								// 遍历的时候，从索引1开始，就是从第二个页面开始
								// 3_2

								list.add(new Tuple2<String, Integer>(pageSplit,
										1));
							}
							lastPageId = pageid;
						}
						return list.listIterator();
					}
				});

	}

	private static JavaPairRDD<String, Integer> reducePageSplit(
			JavaPairRDD<String, Integer> generateAndMatchPageSplit) {

		TreeMap<String, Integer> map = new TreeMap<String, Integer>();
		return generateAndMatchPageSplit
				.reduceByKey(new Function2<Integer, Integer, Integer>() {

					@Override
					public Integer call(Integer v1, Integer v2)
							throws Exception {
						return v1 + v2;
					}
				});

	}

	/**
	 * 计算首页pv
	 * 
	 * @param splitPV
	 */
	private static JavaRDD<Integer> getFirstPagePV(
			JavaPairRDD<String, Integer> splitPV) {
		return splitPV.values().sortBy(new Function<Integer, Integer>() {

			@Override
			public Integer call(Integer pv) throws Exception {
				return pv;
			}
		}, false, 1);

	}

	/**
	 * 将pairRDD装换为Map
	 * 
	 * @param splitAndPV
	 */
	private static Map<String, Integer> getSplitPvMAP(
			JavaPairRDD<String, Integer> splitAndPV) {

		Map<String, Integer> splitPvMap = splitAndPV
				.reduceByKeyLocally(new Function2<Integer, Integer, Integer>() {

					@Override
					public Integer call(Integer v1, Integer v2)
							throws Exception {

						return v1 + v2;
					}
				});
		return splitPvMap;

	}

	/**
	 * 计算split + convertRate
	 * 
	 * @param splitandPV
	 * @param firstPV
	 */

	private static Map<String, Double> getSplitPvRateMap(
			Map<String, Integer> splitPvMAP, Long firstPage) {
		TreeMap<String, Double> convertMap = new TreeMap<String, Double>();

		long lastPageSplitPv = 0L;
		double convertRate = 0.00d;
		for (Entry<String, Integer> map : splitPvMAP.entrySet()) {
			String splitKey = map.getKey();
			Long pvValue = Long.valueOf(map.getValue());

			convertRate = NumberUtils.formatDouble((double) pvValue
					/ (double) firstPage, 2);

			convertMap.put(splitKey, convertRate);
			// System.out.println(splitKey+":"+pvValue);

		}
		/*
		 * System.out.println("!!!###########################");
		 * System.out.println("firstPage:"+firstPage);
		 * System.out.println("!!!###########################");
		 */
		return convertMap;
	}

	/**
	 * 构造一个<-->1,pv>的Map
	 * 
	 * @param splitAndPV
	 */
	private static JavaPairRDD<String, Integer> getfenmuMap(
			JavaPairRDD<String, Integer> splitAndPV) {

		return splitAndPV
				.flatMapToPair(new PairFlatMapFunction<Tuple2<String, Integer>, String, Integer>() {

					@Override
					public Iterator<Tuple2<String, Integer>> call(
							Tuple2<String, Integer> tuple) throws Exception {

						List<Tuple2<String, Integer>> list = new ArrayList<Tuple2<String, Integer>>();
						String a = tuple._1.split("——>")[1];
						Integer b = tuple._2;
						list.add(new Tuple2<String, Integer>(a, b));
						return list.listIterator();
					}
				});

	}

	/**
	 * 对JavaPairRDD<-->1,pv> 的结果进行reducebyKey
	 * 
	 * @param getfenmuMap
	 */
	private static Map<String, Integer> getfenmuMapReduceByKey(
			JavaPairRDD<String, Integer> getfenmuMap) {

		return getfenmuMap
				.reduceByKeyLocally(new Function2<Integer, Integer, Integer>() {

					@Override
					public Integer call(Integer v1, Integer v2)
							throws Exception {
						return v1 + v2;
					}
				});
	}

	/**
	 * 计算相对页面转化率Map
	 * 
	 * @param splitPvMAP
	 * @param splitPvMAP
	 */
	private static Map<String, Double> getSplitRate2Map(
			Map<String, Integer> splitPvMAP, Map<String, Integer> fenmuMapPV) {

		TreeMap<String, Double> rate2Map = new TreeMap<String, Double>();

		double rate2 = 0.00d;
		for (Entry<String, Integer> map1 : splitPvMAP.entrySet()) {
			for (Entry<String, Integer> map2 : fenmuMapPV.entrySet()) {
				if (map1.getKey().split("——>")[0].equals(map2.getKey())) {
					String key = map1.getKey();
					Double rate = NumberUtils.formatDouble(
							Double.valueOf(Long.valueOf(map1.getValue()))
									/ Double.valueOf(Long.valueOf(map2
											.getValue())), 2);
					rate2Map.put(key, rate);
				}

			}
		}

		return rate2Map;

	}

	/**
	 * 持久化到mysql
	 * 
	 * @param splitPvRateMap
	 * @param splitPvMAP2
	 * @param splitRate2Map
	 * @param firstPV 
	 */
	private static void persistConvertRate(Map<String, Double> splitPvRateMap,
			Map<String, Integer> splitPvMAP2, Map<String, Double> splitRate2Map, String firstPV) {

		// 将两个map合并到一个map
		TreeMap<String, String> splitRatePvMap = new TreeMap<String, String>();
		for (Entry<String, Double> map1 : splitPvRateMap.entrySet()) {
			for (Entry<String, Integer> map2 : splitPvMAP2.entrySet()) {
				if (map1.getKey().equals(map2.getKey())) {
					String key = map1.getKey();
					String valuePair = map1.getValue() + "," + map2.getValue();
					splitRatePvMap.put(key, valuePair);
				}
			}
		}

		TreeMap<String, String> treeMap = new TreeMap<String, String>();
		for (Entry<String, String> map1 : splitRatePvMap.entrySet()) {
			for (Entry<String, Double> map2 : splitRate2Map.entrySet()) {
				if (map1.getKey().equals(map2.getKey())) {
					String key = map1.getKey();
					String valuePair = map1.getValue() + "," + map2.getValue();
					treeMap.put(key, valuePair);
					
				}
			}
		}

		PageSplitConvertRate pageSplitConvertRate2 = new PageSplitConvertRate();

		for (Entry<String, String> splitPvMap : treeMap.entrySet()) {
			String Split = splitPvMap.getKey();
			String[] page = Split.split("——>");
			String a = page[0];
			String b = page[1];
			if (!a.equals(b)) {
				String[] split2 = splitPvMap.getValue().split(",");
				String rate1 = split2[0];
				String pv = split2[1];
				String rate2 = split2[2];
				if(Double.valueOf(rate1) <=1 && Double.valueOf(rate2) <= 1){
				pageSplitConvertRate2.setPage_split(Split);// 把split值放入实例化的bean里
				pageSplitConvertRate2.setStart_convert_rate(rate1);// 把pv值放入实例化的bean里
				pageSplitConvertRate2.setPv(pv);// 把pv值放入实例化的bean里
				pageSplitConvertRate2.setLast_convert_rate(rate2);
				pageSplitConvertRate2.setCreate_time(DateUtils
						.formatTimeMinute(new java.util.Date()));

				// 执行插入方法
				AndroidPageSplitConvertRateDAO pageSplitConvertRateDAO = DAOFactory
						.getAndroidPageSplitConvertRateDAO7Day();
				pageSplitConvertRateDAO.insert(pageSplitConvertRate2);
			}
		}
		}
	
	}

}
