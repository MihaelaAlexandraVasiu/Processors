package org.clulab.processors.clu

import org.clulab.processors.clu.sequences._
import org.clulab.processors.clu.syntax._
import org.clulab.processors.clu.tokenizer._
import org.clulab.processors.{Document, Processor, Sentence}
import org.clulab.struct.GraphMap
import com.typesafe.config.{Config, ConfigFactory}
import org.clulab.processors.clu.bio._
import org.clulab.utils.Configured
import org.clulab.utils.ScienceUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import org.clulab.sequences.{LexiconNER, Tagger}
import CluProcessor._

/**
  * Processor that uses only tools that are under Apache License
  * Currently supports:
  *   tokenization (in-house),
  *   lemmatization (Morpha, copied in our repo to minimize dependencies),
  *   POS tagging (in-house BiMEMM),
  *   dependency parsing (ensemble of Malt models) for universal dependencies
  */
class CluProcessor (val config: Config = ConfigFactory.load("cluprocessoropen")) extends Processor with Configured {

  override def getConf: Config = config

  // should we intern strings or not?
  val internStrings:Boolean = getArgBoolean(s"$prefix.internStrings", Some(false))

  // this class post-processes the tokens produced by the tokenizer
  lazy private val tokenizerPostProcessor:Option[TokenizerStep] =
    getArgString(s"$prefix.tokenizer.post.type", Some("none")) match {
      case "bio" => Some(new BioTokenizerPostProcessor(
        getArgStrings(s"$prefix.tokenizer.post.tokensWithValidSlashes", None)
      ))
      case "none" => None
      case _ => throw new RuntimeException(s"ERROR: Unknown argument value for $prefix.tokenizer.post.type!")
    }

  // the actual tokenizer
  lazy val tokenizer: Tokenizer = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => new OpenDomainPortugueseTokenizer(tokenizerPostProcessor)
    case "ES" => new OpenDomainSpanishTokenizer(tokenizerPostProcessor)
    case _ => new OpenDomainEnglishTokenizer(tokenizerPostProcessor)
  }

  // the lemmatizer
  lazy val lemmatizer: Lemmatizer = getArgString(s"$prefix.language", Some("EN")) match {
    case "PT" => new PortugueseLemmatizer
    case "ES" => new SpanishLemmatizer
    case _ => new EnglishLemmatizer
  }

  // the POS tagger
  lazy val posTagger: PartOfSpeechTagger =
    PartOfSpeechTagger.loadFromResource(getArgString(s"$prefix.pos.model", None))

  // this class post-processes the POS tagger to avoid some common tagging mistakes for bio
  lazy val posPostProcessor: Option[SentencePostProcessor] =
    getArgString(s"$prefix.pos.post.type", Some("none")) match {
      case "EN" => Some(new EnglishPOSPostProcessor())
      case "bio" => Some(new BioPOSPostProcessor())
      case "PT" => Some(new PortuguesePOSPostProcessor())
      case "none" => None
      case _ => throw new RuntimeException(s"ERROR: Unknown argument value for $prefix.pos.post.type!")
    }

  // the NER tagger
  lazy val ner: Option[Tagger[String]] =
    getArgString(s"$prefix.ner.type", Some("none")) match {
      case "bio" => Some(LexiconNER(
        getArgStrings(s"$prefix.ner.kbs", None),
        Some(getArgStrings(s"$prefix.ner.overrides", None)),
        new BioLexiconEntityValidator,
        new BioLexicalVariations,
        useLemmasForMatching = false,
        caseInsensitiveMatching = true
      ))
      case "conll" => Some(NamedEntityRecognizer.loadFromResource(getArgString(s"$prefix.ner.model", None)))
      case "none" => None
      case _ => throw new RuntimeException(s"ERROR: Unknown argument value for $prefix.ner.type!")
    }

  // this class post-processes the NER labels to avoid some common tagging mistakes (used in bio)
  lazy val nerPostProcessor: Option[SentencePostProcessor] =
    getArgString(s"$prefix.ner.post.type", Some("none")) match {
      case "bio" => Some(new BioNERPostProcessor(getArgString(s"$prefix.ner.post.stopListFile", None)))
      case "none" => None
      case _ => throw new RuntimeException(s"ERROR: Unknown argument value for $prefix.ner.post.stopListFile!")
    }

  // the syntactic chunker
  lazy val chunker:Option[Chunker] =
    if(contains(s"$prefix.chunker.model"))
      Some(Chunker.loadFromResource(getArgString(s"$prefix.chunker.model", None)))
    else
      None

  // should we use universal dependencies or Stanford ones?
  val useUniversalDependencies:Boolean = getArgBoolean(s"$prefix.parser.universal", Some(true))

  // the dependency parser
  lazy val depParser: Parser =
    if(useUniversalDependencies) {
      //new MaltWrapper(getArgString(s"$prefix.parser.model", None), internStrings)
      new EnsembleMaltParser(getArgStrings(s"$prefix.parser.models-universal", None))
    } else {
      new EnsembleMaltParser(getArgStrings(s"$prefix.parser.models-stanford", None))
    }


  override def annotate(doc:Document): Document = {
    // with this processor, we lemmatize first, because this POS tagger uses lemmas as features
    lemmatize(doc)
    tagPartsOfSpeech(doc)
    recognizeNamedEntities(doc)
    parse(doc)
    chunking(doc)
    resolveCoreference(doc)
    discourse(doc)
    doc.clear()
    doc
  }

  /** Constructs a document of tokens from free text; includes sentence splitting and tokenization */
  def mkDocument(text:String, keepText:Boolean = false): Document = {
    CluProcessor.mkDocument(tokenizer, text, keepText)
  }

  /** Constructs a document of tokens from an array of untokenized sentences */
  def mkDocumentFromSentences(sentences:Iterable[String],
                              keepText:Boolean = false,
                              charactersBetweenSentences:Int = 1): Document = {
    CluProcessor.mkDocumentFromSentences(tokenizer, sentences, keepText, charactersBetweenSentences)
  }

  /** Constructs a document of tokens from an array of tokenized sentences */
  def mkDocumentFromTokens(sentences:Iterable[Iterable[String]],
                           keepText:Boolean = false,
                           charactersBetweenSentences:Int = 1,
                           charactersBetweenTokens:Int = 1): Document = {
    CluProcessor.mkDocumentFromTokens(tokenizer, sentences, keepText, charactersBetweenSentences, charactersBetweenTokens)
  }

  /** Part of speech tagging */
  def tagPartsOfSpeech(doc:Document) {
    basicSanityCheck(doc)
    for(sent <- doc.sentences) {
      val tags = posTagger.classesOf(sent)
      sent.tags = Some(tags)

      if(posPostProcessor.nonEmpty) {
        posPostProcessor.get.process(sent)
      }
    }
  }

  /** Lematization; modifies the document in place */
  def lemmatize(doc:Document) {
    basicSanityCheck(doc)
    for(sent <- doc.sentences) {
      //println(s"Lemmatize sentence: ${sent.words.mkString(", ")}")
      val lemmas = new Array[String](sent.size)
      for(i <- sent.words.indices) {
        lemmas(i) = lemmatizer.lemmatizeWord(sent.words(i))
        assert(lemmas(i).nonEmpty)
      }
      sent.lemmas = Some(lemmas)
    }
  }

  /** NER; modifies the document in place */
  def recognizeNamedEntities(doc:Document) {
    if(ner.nonEmpty) {
      basicSanityCheck(doc)
      for (sentence <- doc.sentences) {
        val labels = ner.get.find(sentence)
        sentence.entities = Some(labels)

        if(nerPostProcessor.nonEmpty) {
          nerPostProcessor.get.process(sentence)
        }
      }
    }
  }

  /** Syntactic parsing; modifies the document in place */
  def parse(doc:Document) {
    basicSanityCheck(doc)
    if (doc.sentences.head.tags.isEmpty)
      throw new RuntimeException("ERROR: you have to run the POS tagger before parsing!")
    if (doc.sentences.head.lemmas.isEmpty)
      throw new RuntimeException("ERROR: you have to run the lemmatizer before parsing!")

    for (sentence <- doc.sentences) {
      //println(s"PARSING SENTENCE: ${sentence.words.mkString(", ")}")
      //println(sentence.tags.get.mkString(", "))
      //println(sentence.lemmas.get.mkString(", "))
      val dg = depParser.parseSentence(sentence)

      if(useUniversalDependencies) {
        sentence.setDependencies(GraphMap.UNIVERSAL_BASIC, dg)
        sentence.setDependencies(GraphMap.UNIVERSAL_ENHANCED,
          EnhancedDependencies.generateUniversalEnhancedDependencies(sentence, dg))
      } else {
        sentence.setDependencies(GraphMap.STANFORD_BASIC, dg)
        sentence.setDependencies(GraphMap.STANFORD_COLLAPSED,
          EnhancedDependencies.generateStanfordEnhancedDependencies(sentence, dg))
      }
    }
  }

  /** Shallow parsing; modifies the document in place */
  def chunking(doc:Document) {
    if(chunker.isDefined) {
      basicSanityCheck(doc)
      for (sent <- doc.sentences) {
        val chunks = chunker.get.classesOf(sent)
        sent.chunks = Some(chunks)
      }
    }
  }

  /** Coreference resolution; modifies the document in place */
  def resolveCoreference(doc:Document) {
    // TODO
  }

  /** Discourse parsing; modifies the document in place */
  def discourse(doc:Document) {
    // TODO
  }

  /** Relation extraction; modifies the document in place. */
  override def relationExtraction(doc: Document): Unit = {
    // TODO
  }

  def basicSanityCheck(doc:Document): Unit = {
    if (doc.sentences == null)
      throw new RuntimeException("ERROR: Document.sentences == null!")
    if (doc.sentences.length != 0 && doc.sentences(0).words == null)
      throw new RuntimeException("ERROR: Sentence.words == null!")
  }

}

trait SentencePostProcessor {
  def process(sentence: Sentence)
}

/** Same as CluProcessor but it includes custom tokenization and NER for the bio domain */
class BioCluProcessor extends CluProcessor(config = ConfigFactory.load("cluprocessorbio"))

/** Same as CluProcessor but using Stanford dependencies */
class CluProcessorWithStanford extends CluProcessor(config = ConfigFactory.load("cluprocessoropenwithstanford"))

/** CluProcessor for Spanish */
class SpanishCluProcessor extends CluProcessor(config = ConfigFactory.load("cluprocessorspanish"))

/** CluProcessor for Portuguese */
class PortugueseCluProcessor extends CluProcessor(config = ConfigFactory.load("cluprocessorportuguese")) {

  val scienceUtils = new ScienceUtils

  /** Constructs a document of tokens from free text; includes sentence splitting and tokenization */
  override def mkDocument(text:String, keepText:Boolean = false): Document = {
    // FIXME by calling replaceUnicodeWithAscii we are normalizing unicode and keeping accented characters of interest,
    // but we are also replacing individual unicode characters with sequences of characters that can potentially be greater than one
    // which means we may lose alignment to the original text
    val textWithAccents = scienceUtils.replaceUnicodeWithAscii(text, keepAccents = true)
    CluProcessor.mkDocument(tokenizer, textWithAccents, keepText)
  }

  // overrided this because lemmatization depends on POS for portuguese
  override def annotate(doc:Document): Document = {
    tagPartsOfSpeech(doc)
    lemmatize(doc)
    recognizeNamedEntities(doc)
    parse(doc)
    chunking(doc)
    resolveCoreference(doc)
    discourse(doc)
    doc.clear()
    doc
  }

  /** Lematization; modifies the document in place */
  override def lemmatize(doc:Document) {
    basicSanityCheck(doc)
    for(sent <- doc.sentences) {
      //println(s"Lemmatize sentence: ${sent.words.mkString(", ")}")
      val lemmas = new Array[String](sent.size)
      for(i <- sent.words.indices) {
        lemmas(i) = lemmatizer.lemmatizeWord(sent.words(i))
        assert(lemmas(i).nonEmpty)
      }
      sent.lemmas = Some(lemmas)
    }
  }

}

object CluProcessor {
  val logger:Logger = LoggerFactory.getLogger(classOf[CluProcessor])
  val prefix:String = "CluProcessor"

  /** Constructs a document of tokens from free text; includes sentence splitting and tokenization */
  def mkDocument(tokenizer:Tokenizer,
                 text:String,
                 keepText:Boolean): Document = {
    val sents = tokenizer.tokenize(text)
    val doc = new Document(sents)
    if(keepText) doc.text = Some(text)
    doc
  }

  /** Constructs a document of tokens from an array of untokenized sentences */
  def mkDocumentFromSentences(tokenizer:Tokenizer,
                              sentences:Iterable[String],
                              keepText:Boolean,
                              charactersBetweenSentences:Int): Document = {
    val sents = new ArrayBuffer[Sentence]()
    var characterOffset = 0
    for(text <- sentences) {
      val sent = tokenizer.tokenize(text, sentenceSplit = false).head // we produce a single sentence here!

      // update character offsets between sentences
      for(i <- 0 until sent.size) {
        sent.startOffsets(i) += characterOffset
        sent.endOffsets(i) += characterOffset
      }

      // move the character offset after the current sentence
      characterOffset = sent.endOffsets.last + charactersBetweenSentences

      //println("SENTENCE: " + sent.words.mkString(", "))
      //println("Start offsets: " + sent.startOffsets.mkString(", "))
      //println("End offsets: " + sent.endOffsets.mkString(", "))
      sents += sent
    }
    val doc = new Document(sents.toArray)
    if(keepText) doc.text = Some(sentences.mkString(mkSep(charactersBetweenSentences)))
    doc
  }

  /** Constructs a document of tokens from an array of tokenized sentences */
  def mkDocumentFromTokens(tokenizer:Tokenizer,
                           sentences:Iterable[Iterable[String]],
                           keepText:Boolean,
                           charactersBetweenSentences:Int,
                           charactersBetweenTokens:Int): Document = {
    var charOffset = 0
    var sents = new ArrayBuffer[Sentence]()
    val text = new StringBuilder
    for(sentence <- sentences) {
      val startOffsets = new ArrayBuffer[Int]()
      val endOffsets = new ArrayBuffer[Int]()
      for(word <- sentence) {
        startOffsets += charOffset
        charOffset += word.length
        endOffsets += charOffset
        charOffset += charactersBetweenTokens
      }
      // note: NO postprocessing happens in this case, so use it carefully!
      sents += new Sentence(sentence.toArray, startOffsets.toArray, endOffsets.toArray, sentence.toArray)
      charOffset += charactersBetweenSentences - charactersBetweenTokens
      if(keepText) {
        text.append(sentence.mkString(mkSep(charactersBetweenTokens)))
        text.append(mkSep(charactersBetweenSentences))
      }
    }

    val doc = new Document(sents.toArray)
    if(keepText) doc.text = Some(text.toString)
    doc
  }

  private def mkSep(size:Int):String = {
    val os = new mutable.StringBuilder
    for (_ <- 0 until size) os.append(" ")
    os.toString()
  }
}


