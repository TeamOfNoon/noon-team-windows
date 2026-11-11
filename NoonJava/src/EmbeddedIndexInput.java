import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.apache.lucene.store.*;

/** Bounded, buffered random reads from an embedded resource; never extracts it. */
final class EmbeddedIndexInput extends BufferedIndexInput {
 private final FileChannel source;
 private final byte[] memory;
 private final long offset,size;
 private boolean owner=true,closed;
 EmbeddedIndexInput(String name,File file,long offset,long size,IOContext context)throws IOException{
  super(name,context);this.source=FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.READ);this.offset=offset;this.size=size;this.memory=null;
 }
 EmbeddedIndexInput(String name,byte[] bytes,IOContext context){super(name,context);source=null;memory=bytes;offset=0;size=bytes.length;}
 private void checkOpen(){if(closed)throw new AlreadyClosedException("Embedded index input closed");}
 protected void readInternal(byte[] bytes,int start,int count)throws IOException{
  checkOpen();long position=getFilePointer();if(position<0||count>size-position)throw new EOFException("Read beyond embedded resource");
  if(memory!=null){System.arraycopy(memory,(int)position,bytes,start,count);return;}
  ByteBuffer buffer=ByteBuffer.wrap(bytes,start,count);long at=offset+position;
  while(buffer.hasRemaining()){int n=source.read(buffer,at);if(n<0)throw new EOFException("Embedded index resource was truncated");at+=n;}
 }
 protected void seekInternal(long position)throws IOException{checkOpen();if(position<0||position>size)throw new EOFException("Seek beyond embedded resource");}
 public long length(){return size;}
 public EmbeddedIndexInput clone(){checkOpen();EmbeddedIndexInput clone=(EmbeddedIndexInput)super.clone();clone.owner=false;return clone;}
 public void close()throws IOException{if(closed)return;closed=true;if(owner&&source!=null)source.close();}
}
