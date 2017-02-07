package scorex.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import play.api.libs.json.Json
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.block.Block.BlockId
import scorex.consensus.ConsensusModule
import scorex.consensus.nxt.{NxtConsensusBlockField, NxtLikeConsensusBlockData}
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import scorex.transaction.{AssetAcc, Transaction, TransactionModule, TransactionsBlockField}
import scorex.utils.ScorexLogging
import scorex.transaction.TypedTransaction._

import scala.util.{Failure, Try}

case class Block(timestamp: Long, version: Byte, reference: Block.BlockId, signerData: SignerData,
                 consensusDataField: BlockField[NxtLikeConsensusBlockData], transactionDataField: BlockField[Seq[Transaction]]) {

  val versionField: ByteBlockField = ByteBlockField("version", version)
  val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
  val referenceField: BlockIdField = BlockIdField("reference", reference)
  val signerDataField: SignerDataBlockField = SignerDataBlockField("signature", signerData)
  val uniqueId: BlockId = signerData.signature
  lazy val encodedId: String = Base58.encode(uniqueId)

  lazy val fee = {
    val generator = signerData.generator
    val assetFees = transactionDataField.asInstanceOf[TransactionsBlockField].value.map(_.assetFee)
    assetFees.map(a => AssetAcc(generator, a._1) -> a._2).groupBy(a => a._1).mapValues(_.map(_._2).sum)
  }.values.sum

  lazy val json =
    versionField.json ++
      timestampField.json ++
      referenceField.json ++
      consensusDataField.json ++
      transactionDataField.json ++
      signerDataField.json ++
      Json.obj(
        "fee" -> fee,
        "blocksize" -> bytes.length
      )

  lazy val bytes = {
    val txBytesSize = transactionDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionDataField.bytes

    val cBytesSize = consensusDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusDataField.bytes

    versionField.bytes ++
      timestampField.bytes ++
      referenceField.bytes ++
      cBytes ++
      txBytes ++
      signerDataField.bytes
  }

  lazy val bytesWithoutSignature = bytes.dropRight(SignatureLength)

  override def equals(obj: scala.Any): Boolean = {
    import shapeless.syntax.typeable._
    obj.cast[Block].exists(_.uniqueId.sameElements(this.uniqueId))
  }
}


object Block extends ScorexLogging {
  type BlockId = Array[Byte]
  type BlockIds = Seq[BlockId]

  val BlockIdLength = SignatureLength

  def feesDistribution(block: Block): Map[AssetAcc, Long] = {
    val generator = block.signerDataField.value.generator
    val assetFees = block.transactionDataField.asInstanceOf[TransactionsBlockField].value.map(_.assetFee)
    assetFees.map(a => AssetAcc(generator, a._1) -> a._2).groupBy(a => a._1).mapValues(_.map(_._2).sum)
  }

  def parseBytes(bytes: Array[Byte])
                (implicit consModule: ConsensusModule,
                 transModule: TransactionModule): Try[Block] = Try {

    val version = bytes.head

    var position = 1

    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position += 8

    val reference = bytes.slice(position, position + Block.BlockIdLength)
    position += BlockIdLength

    val cBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val cBytes = bytes.slice(position, position + cBytesLength)
    val consBlockField = consModule.parseBytes(cBytes).get
    position += cBytesLength

    val tBytesLength = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4
    val tBytes = bytes.slice(position, position + tBytesLength)
    val txBlockField = transModule.parseBytes(tBytes).get
    position += tBytesLength

    val genPK = bytes.slice(position, position + KeyLength)
    position += KeyLength

    val signature = bytes.slice(position, position + SignatureLength)

    new Block(timestamp, version, reference, SignerData(new PublicKeyAccount(genPK), signature), consBlockField, txBlockField)
  }.recoverWith { case t: Throwable =>
    log.error("Error when parsing block", t)
    t.printStackTrace()
    Failure(t)
  }

  def build(version: Byte,
            timestamp: Long,
            reference: BlockId,
            consensusData: NxtLikeConsensusBlockData,
            transactionData: Seq[Transaction],
            generator: PublicKeyAccount,
            signature: Array[Byte]): Block = {
    new Block(timestamp, version, reference, SignerData(generator, signature), NxtConsensusBlockField(consensusData), TransactionsBlockField(transactionData))

  }

  def buildAndSign(version: Byte,
                   timestamp: Long,
                   reference: BlockId,
                   consensusData: NxtLikeConsensusBlockData,
                   transactionData: Seq[Transaction],
                   signer: PrivateKeyAccount): Block = {
    val nonSignedBlock = build(version, timestamp, reference, consensusData, transactionData, signer, Array())
    val toSign = nonSignedBlock.bytes
    val signature = EllipticCurveImpl.sign(signer, toSign)
    build(version, timestamp, reference, consensusData, transactionData, signer, signature)
  }

  def genesis(concensusGenesisData: BlockField[NxtLikeConsensusBlockData],
              transactionGenesisData: BlockField[Seq[Transaction]],
              timestamp: Long = 0L,
              signatureStringOpt: Option[String] = None): Block = {
    val version: Byte = 1

    val genesisSigner = new PrivateKeyAccount(Array.empty)

    //    val transactionGenesisData: BlockField[Seq[Transaction]] = transModule.genesisData
    //    val concensusGenesisData: BlockField[NxtLikeConsensusBlockData] = consModule.genesisData
    val txBytesSize = transactionGenesisData.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionGenesisData.bytes
    val cBytesSize = concensusGenesisData.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ concensusGenesisData.bytes

    val reference = Array.fill(BlockIdLength)(-1: Byte)

    val toSign: Array[Byte] = Array(version) ++
      Bytes.ensureCapacity(Longs.toByteArray(timestamp), 8, 0) ++
      reference ++
      cBytes ++
      txBytes ++
      genesisSigner.publicKey

    val signature = signatureStringOpt.map(Base58.decode(_).get)
      .getOrElse(EllipticCurveImpl.sign(genesisSigner, toSign))

    require(EllipticCurveImpl.verify(signature, toSign, genesisSigner.publicKey), "Passed genesis signature is not valid")

    new Block(timestamp = timestamp,
      version = 1,
      reference = reference,
      signerData = SignerData(genesisSigner, signature),
      consensusDataField = concensusGenesisData,
      transactionDataField = transactionGenesisData)
  }
}
