package org.clulab.processors.coserver

import akka.actor.{ ActorRef, Props, Actor }
import akka.event.Logging

import org.clulab.processors._
import org.clulab.processors.corenlp._

import ProcessorCoreServerMessages._

/**
  * Actor which handles message to a Processor in the CoreNLPServer.
  *   Written by: Tom Hicks. 6/6/2017.
  *   Last Modified: Restore/handle annotator methods.
  */
class ProcessorActor (

  /** The Processor to use within this actor. */
  val processor: Processor

) extends Actor {

  val log = Logging(context.system, this)

  override def preStart (): Unit =
    log.debug(s"Actor ${self} starting")

  override def preRestart (reason: Throwable, msg: Option[Any]): Unit = {
    log.debug(s"Actor ${self} restarting...")
    super.preRestart(reason, msg)
  }

  override def postRestart (reason: Throwable): Unit = {
    log.debug(s"Actor ${self} ...restarted")
    super.postRestart(reason)
  }

  override def postStop (): Unit =
    log.debug(s"Actor ${self} stopped")


  def receive = {
    case cmd: MkDocumentCmd =>
      log.debug(s"(ProcessorActor.receive): mkDocument(text=${cmd.text}, keep=${cmd.keepText}")
      val doc = processor.mkDocument(cmd.text, cmd.keepText)
      sender ! DocumentMsg(doc)

    case cmd: MkDocumentFromSentencesCmd =>
      log.debug(s"(ProcessorActor.receive): mkDocumentFromSentences(sents=${cmd.sentences}, keep=${cmd.keepText}, charsBTWSents=${cmd.charactersBetweenSentences}")
      val doc = processor.mkDocumentFromSentences(
        cmd.sentences, cmd.keepText, cmd.charactersBetweenSentences)
      sender ! DocumentMsg(doc)

    case cmd: MkDocumentFromTokensCmd =>
      log.debug(s"(ProcessorActor.receive): mkDocumentFromTokens(sents=${cmd.sentences}, keep=${cmd.keepText}, charsBTWSents=${cmd.charactersBetweenSentences}, charsBTWToks=${cmd.charactersBetweenTokens}")
      val doc = processor.mkDocumentFromTokens(
        cmd.sentences, cmd.keepText, cmd.charactersBetweenSentences, cmd.charactersBetweenTokens)
      sender ! DocumentMsg(doc)

    case cmd: PreprocessTextCmd =>
      log.debug(s"(ProcessorActor.receive): preprocessText(text=${cmd.text}")
      val pptext = processor.preprocessText(cmd.text)
      sender ! TextMsg(pptext)

    case cmd: PreprocessSentencesCmd =>
      log.debug(s"(ProcessorActor.receive): preprocessSentences(sents=${cmd.sentences}")
      val ppsents = processor.preprocessSentences(cmd.sentences)
      sender ! SentencesMsg(ppsents)

    case cmd: PreprocessTokensCmd =>
      log.debug(s"(ProcessorActor.receive): preprocessTokens(sents=${cmd.sentences}")
      val pptoks = processor.preprocessTokens(cmd.sentences)
      sender ! TokensMsg(pptoks)


    // LATER: The following annotators modify the document in place, which does not work with Akka
    case cmd: TagPartsOfSpeechCmd =>
      log.debug(s"(ProcessorActor.receive): tagPartsOfSpeech(doc=${cmd.doc}")
      processor.tagPartsOfSpeech(cmd.doc)   // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: LemmatizeCmd =>
      log.debug(s"(ProcessorActor.receive): lemmatize(doc=${cmd.doc}")
      processor.lemmatize(cmd.doc)          // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: RecognizeNamedEntitiesCmd =>
      log.debug(s"(ProcessorActor.receive): recognizeNamedEntities(doc=${cmd.doc}")
        processor.recognizeNamedEntities(cmd.doc)  // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: ParseCmd =>
      log.debug(s"(ProcessorActor.receive): parse(doc=${cmd.doc}")
      processor.parse(cmd.doc)              // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: ChunkingCmd =>
      log.debug(s"(ProcessorActor.receive): chunking(doc=${cmd.doc}")
      processor.chunking(cmd.doc)           // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: LabelSemanticRolesCmd =>
      log.debug(s"(ProcessorActor.receive): labelSemanticRoles(doc=${cmd.doc}")
      processor.labelSemanticRoles(cmd.doc) // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: ResolveCoreferenceCmd =>
      log.debug(s"(ProcessorActor.receive): resolveCoreference(doc=${cmd.doc}")
      processor.resolveCoreference(cmd.doc) // works by side-effect
      sender ! DocumentMsg(cmd.doc)

    case cmd: DiscourseCmd =>
      log.debug(s"(ProcessorActor.receive): discourse(doc=${cmd.doc}")
      processor.discourse(cmd.doc)          // works by side-effect
      sender ! DocumentMsg(cmd.doc)


    case cmd: AnnotateFromSentencesCmd =>
      log.debug(s"(ProcessorActor.receive): annotateFromSentences(sents=${cmd.sentences}, keep=${cmd.keepText}")
      val doc = processor.annotateFromSentences(cmd.sentences, cmd.keepText)
      sender ! DocumentMsg(doc)

    case cmd: AnnotateFromTokensCmd =>
      log.debug(s"(ProcessorActor.receive): annotateFromTokens(sents=${cmd.sentences}, keep=${cmd.keepText}")
      val doc = processor.annotateFromTokens(cmd.sentences, cmd.keepText)
      sender ! DocumentMsg(doc)

    case cmd: AnnotateTextCmd =>
      log.debug(s"(ProcessorActor.receive): annotateText(text=${cmd.text}, keep=${cmd.keepText}")
      val doc = processor.annotate(cmd.text, cmd.keepText)
      sender ! DocumentMsg(doc)

    case cmd: AnnotateCmd =>
      log.debug(s"(ProcessorActor.receive): annotate(doc=${cmd.doc}")
      val doc = processor.annotate(cmd.doc)
      sender ! DocumentMsg(doc)

    case unknown =>
      log.error(s"ProcessorActor: unrecognized message: ${unknown}")
      sender ! TextMsg(s"ProcessorActor: unrecognized message: ${unknown}")
  }

}

object ProcessorActor {
  /**
   * Constructor to create Props for an actor of this type.
   *   @param processor The Processor to be passed to this actor’s constructor.
   *   @return a Props for creating this actor.
   */
  def props (processor: Processor): Props = Props(new ProcessorActor(processor))
}
