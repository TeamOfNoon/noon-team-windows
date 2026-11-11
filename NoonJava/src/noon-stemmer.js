/* NOON_ROOT_SEARCH_V1: mirrors Lucene Arabic light stemming and Porter stemming. */
(function(g){
 if(g.noonStemWord)return;
 function arabic(w){
  w=w.replace(/[أإآ]/g,'ا').replace(/ى/g,'ي').replace(/ة/g,'ه').replace(/[\u0640\u064b-\u0652]/g,'');
  var pre=['ال','وال','بال','كال','فال','لل','و'],suf=['ها','ان','ات','ون','ين','يه','ية','ه','ة','ي'],i,s;
  for(i=0;i<pre.length;i++){s=pre[i];if(w.length>=s.length+2&&(s.length!==1||w.length>=4)&&w.indexOf(s)===0){w=w.slice(s.length);break;}}
  for(i=0;i<suf.length;i++){s=suf[i];if(w.length>=s.length+2&&w.slice(-s.length)===s)w=w.slice(0,-s.length);}
  return w;
 }
 function porter(w){
  if(w.length<3)return w;
  function consonant(s,i){var c=s.charAt(i);if(/[aeiou]/.test(c))return false;if(c==='y')return i===0||!consonant(s,i-1);return true;}
  function measure(s){var n=0,inVowel=false;for(var i=0;i<s.length;i++){if(consonant(s,i)){if(inVowel)n++;inVowel=false;}else inVowel=true;}return n;}
  function vowel(s){for(var i=0;i<s.length;i++)if(!consonant(s,i))return true;return false;}
  function doubleC(s){var n=s.length;return n>1&&s.charAt(n-1)===s.charAt(n-2)&&consonant(s,n-1);}
  function cvc(s){var n=s.length;return n>2&&consonant(s,n-1)&&!consonant(s,n-2)&&consonant(s,n-3)&&!/[wxy]/.test(s.charAt(n-1));}
  function ends(s){return w.slice(-s.length)===s;}
  // Steps 1a, 1b, 1c.
  if(ends('sses'))w=w.slice(0,-2);else if(ends('ies'))w=w.slice(0,-2);else if(!ends('ss')&&ends('s'))w=w.slice(0,-1);
  if(ends('eed')){if(measure(w.slice(0,-3))>0)w=w.slice(0,-1);}
  else {var suffix=ends('ed')?'ed':ends('ing')?'ing':'';if(suffix&&vowel(w.slice(0,-suffix.length))){w=w.slice(0,-suffix.length);if(/(at|bl|iz)$/.test(w))w+='e';else if(doubleC(w)&&!/[lsz]$/.test(w))w=w.slice(0,-1);else if(measure(w)===1&&cvc(w))w+='e';}}
  if(ends('y')&&vowel(w.slice(0,-1)))w=w.slice(0,-1)+'i';
  function replaceSuffix(rules){for(var i=0;i<rules.length;i++){var s=rules[i][0];if(ends(s)){var base=w.slice(0,-s.length);if(measure(base)>0)w=base+rules[i][1];return;}}}
  replaceSuffix([['ational','ate'],['tional','tion'],['enci','ence'],['anci','ance'],['izer','ize'],['bli','ble'],['alli','al'],['entli','ent'],['eli','e'],['ousli','ous'],['ization','ize'],['ation','ate'],['ator','ate'],['alism','al'],['iveness','ive'],['fulness','ful'],['ousness','ous'],['aliti','al'],['iviti','ive'],['biliti','ble'],['logi','log']]);
  replaceSuffix([['icate','ic'],['ative',''],['alize','al'],['iciti','ic'],['ical','ic'],['ful',''],['ness','']]);
  var endings=['al','ance','ence','er','ic','able','ible','ant','ement','ment','ent','ion','ou','ism','ate','iti','ous','ive','ize'];
  for(var i=0;i<endings.length;i++){var s=endings[i];if(ends(s)){var base=w.slice(0,-s.length);if(measure(base)>1&&(s!=='ion'||/[st]$/.test(base)))w=base;break;}}
  if(ends('e')){var base=w.slice(0,-1),m=measure(base);if(m>1||(m===1&&!cvc(base)))w=base;}
  if(measure(w)>1&&doubleC(w)&&ends('l'))w=w.slice(0,-1);
  return w;
 }
 var cache={},cacheSize=0;
 g.noonStemWord=function(w){w=String(w||'').toLowerCase();var key='$'+w;if(Object.prototype.hasOwnProperty.call(cache,key))return cache[key];var stem=porter(arabic(w));if(cacheSize>=10000){cache={};cacheSize=0;}cache[key]=stem;cacheSize++;return stem;};
 g.noonRootQueryParts=function(parts,kind,mode){if(kind!=='root_index'||!/^(all|any)$/.test(String(mode||'')))return parts;var seen={},out=[],i,j,words,key;for(i=0;i<parts.length;i++){words=parts[i].q.split(' ');for(j=0;j<words.length;j++)if(words[j]){key='$'+g.noonStemWord(words[j]);if(!seen[key]){seen[key]=true;out.push({q:words[j],quoted:false});}}}return out;};
 g.noonRootRanges=function(text,parts,mode,termIndex){
  var terms=[],raw=[],words=[],out=[],seen={},re=/[^ ]+/g,m,i,j,k,q,stem,whole;
  for(i=0;i<parts.length;i++){var split=parts[i].q.split(' ');for(j=0;j<split.length;j++)if(split[j]){q=split[j];stem=g.noonStemWord(q);if(mode==='phrase'||mode==='exact'||!seen['$'+stem]){terms.push(stem);raw.push(q);seen['$'+stem]=true;}}}
  if(!terms.length)return out;
  while((m=re.exec(text)))words.push({q:m[0],stem:g.noonStemWord(m[0]),start:m.index,end:m.index+m[0].length-1});
  var phrase=mode==='phrase'||mode==='exact';
  for(i=0;i<words.length;i++){
   if(phrase){if(i+terms.length>words.length)break;whole=true;for(j=0;j<terms.length;j++){if(words[i+j].stem!==terms[j])break;if(words[i+j].q!==raw[j])whole=false;}if(j===terms.length)out.push({start:words[i].start,end:words[i+j-1].end,boundary:whole,termIndex:termIndex||0,previewKey:words[i].q});}
   else for(k=0;k<terms.length;k++)if(words[i].stem===terms[k]){out.push({start:words[i].start,end:words[i].end,boundary:words[i].q===raw[k],termIndex:termIndex==null?k:termIndex,previewKey:words[i].q});break;}
  }
  out.sort(function(a,b){return(a.boundary===b.boundary?0:a.boundary?-1:1)||a.start-b.start||(a.termIndex||0)-(b.termIndex||0);});
  for(i=0;i<out.length;i++)out[i].id=i+1;
  return out;
 };
})(window);
