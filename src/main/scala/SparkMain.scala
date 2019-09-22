package JsonExplorer

import java.io._
import java.util.Calendar

//import BiMax.OurBiMax
import Explorer.Types.AttributeName
import Explorer._
import Optimizer.RewriteAttributes
import org.apache.spark.storage.StorageLevel
import util.CMDLineParser
import org.apache.spark.util.SizeEstimator

import scala.collection.mutable


object SparkMain {


  def main(args: Array[String]): Unit = {


    val log: mutable.ListBuffer[LogOutput] = mutable.ListBuffer[LogOutput]()
    log += LogOutput("Date",Calendar.getInstance().getTime().toString,"Date: ")

    val config = CMDLineParser.readArgs(args) // Creates the Spark session with its config values.

    val startTime = System.currentTimeMillis() // Start timer


    /*
      Shred the input file into JsonExplorerTypes while keeping the JSON tree structure.
      This can then be parsed in the feature vector creation phase without having to re-read the input file.
     */
    val shreddedRecords = config.train.mapPartitions(x=>JacksonShredder.shred(x))


    // for storage comparison
    config.memory match {
      case Some(inmemory) =>
        if(inmemory)
          shreddedRecords.persist(StorageLevel.MEMORY_ONLY)
        else
          shreddedRecords.persist(StorageLevel.DISK_ONLY)
      case None => // don't cache at all
    }

    /*
      Preform the extraction phase:
        - Traverses the shredded JsonExplorerObject
        - Collects each attributes type information and co-occurrence-lite
        - This can then be converted into key-space and type entropy
     */

    val extractedAttributes: Array[(AttributeName,Attribute)] = shreddedRecords
      .flatMap(Extract.ExtractAttributes(_))
      .combineByKey(Extract.createCombiner,Extract.mergeValue,Extract.mergeCombiners)
      .map{case(n,t) => {
        (n,Attribute(n,t))
      }}.collect()


    val extractionTime = System.currentTimeMillis()
    val extractionRunTime = extractionTime - startTime

    val attributeTree: AttributeTree = RewriteAttributes.attributeListToAttributeTree(extractedAttributes)

    // set objectKeySpaceThreshold to 0.0 to disable var_objects
    RewriteAttributes.rewriteSemanticTypes(attributeTree,1.0,0.0,1.0)

    // TODO check type entropy, might be a bit screwy since it was negative
    // get schemas to break on
    val schemas: Seq[AttributeName] = RewriteAttributes.getSchemas(attributeTree)
      .map(x => x.map(y => if(y.isInstanceOf[Int]) Star else y)) // remove possibility of weird array stuff
      .toSeq
      .sortBy(_.size)(Ordering[Int].reverse)

    val optimizationTime = System.currentTimeMillis()
    val optimizationRunTime = optimizationTime - extractionTime

    // TODO feature vectorization
    val featureVectors: Map[AttributeName,mutable.HashMap[Map[AttributeName,mutable.Set[JsonExplorerType]],Int]] = shreddedRecords.flatMap(FeatureVectors.create(schemas,_))
      .combineByKey(FeatureVectors.createCombiner,FeatureVectors.mergeValue,FeatureVectors.mergeCombiners).collect().toMap


    // TODO BiMax algorithm

    //println("Optimization Took: " + optimizationRunTime.toString + " ms")
    // create feature vectors from this list

//    var (orig,kmeans,bimax): (Array[Array[Double]],Array[Array[Double]],Array[Array[Double]]) = (null,null,null)
//    var bimaxSchema: ListBuffer[(mutable.HashSet[Types.AttributeName], mutable.HashSet[Types.AttributeName])]  = null
//
//    val fvs = shreddedRecords.flatMap(FeatureVectorCreator.extractFVSs(root.Schemas,_))
//      .combineByKey(FeatureVectorCreator.createCombiner,FeatureVectorCreator.mergeValue,FeatureVectorCreator.mergeCombiners)
//      //.reduceByKey(FeatureVectorCreator.Combine(_,_)).map(x => FeatureVectorCreator.toDense(x._1,x._2))
//
//      val r = fvs.map(x => BiMax.OurBiMax.BiMax(x._1,x._2)).map(x => BiMax.OurBiMax.convertBiMaxNodes(x._1,x._2)).map(x => BiMax.OurBiMax.categorizeAttributes(x._1,x._2)).collect()
//      val (g,l) = BiMax.OurBiMax.buildGraph(root,r)
//      log += LogOutput("Precision",BiMax.OurBiMax.calculatePrecision(ListBuffer[Any](),g,l).toString(),"Precision: ")
//      val schemaSet = OurBiMax.graphToSchemaSet(root,g,l)
//      bimaxSchema = schemaSet.map { case (m, o) => Tuple2(mutable.HashSet[AttributeName](), m ++ o) }
//      log += LogOutput("Grouping",schemaSet.size.toString(),"Number of Groups: ")


    val endTime = System.currentTimeMillis() // End Timer
    val FVRunTime = endTime - optimizationTime
    log += LogOutput("ExtractionTime",extractionRunTime.toString,"Extraction Took: "," ms")
    log += LogOutput("OptimizationTime",optimizationRunTime.toString,"Optimization Took: "," ms")
    log += LogOutput("FVCreationTime",FVRunTime.toString,"FV Creation Took: "," ms")
    log += LogOutput("TotalTime",(endTime - startTime).toString,"Total execution time: ", " ms")

    val attributeMap = RewriteAttributes.attributeTreeToAttributeList(attributeTree)
    println(SizeEstimator.estimate(featureVectors.filter(x=> x._1.isEmpty || !RewriteAttributes.unwrap(attributeMap.get(x._1).get.`type`).contains(JE_Var_Object))))
    println(SizeEstimator.estimate(featureVectors))


    val logFile = new FileWriter("log.json",true)
    logFile.write("{" + log.map(_.toJson).mkString(",") + "}\n")
    logFile.close()
    println(log.map(_.toString).mkString("\n"))

  }

  case class LogOutput(label:String, value:String, printPrefix:String, printSuffix:String = ""){
    override def toString: String = s"""${printPrefix}${value}${printSuffix}"""
    def toJson: String = s""""${label}":"${value}""""
  }

}
