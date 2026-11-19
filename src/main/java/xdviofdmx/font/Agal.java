package xdviofdmx.font;

import java.util.HashMap;
import java.util.Map;

/**
 * PostScript 字形名 → Unicode 推导（AGLCN 规则的精选实现）。
 *
 * <p>来源：内置 Adobe Glyph List 核心子集（拉丁/希腊/标点/货币/数学符号/箭头/
 * 连字，覆盖 T1/TS1 与常见符号字体命名的绝大部分）+ 标准复合规则
 * {@code uniXXXX}/{@code uXXXX}。查不到的名字返回 null（上层轮廓兜底，
 * 视觉不受影响，仅文本抽取缺失）。</p>
 */
public final class Agal {

    private static final Map<String, Integer> MAP = build();

    private Agal() {
    }

    /**
     * 字形名 → 码位；规则：uniXXXX/uXXXX 复合解析优先于 AGL 表（两者可共存，
     * 如 uni00AD 与 hyphen）。查不到返回 -1。
     */
    public static int codePointOf(String glyphName) {
        if (glyphName == null || glyphName.isEmpty()) {
            return -1;
        }
        // 注意先查 AGL 表：单字母字形名 "u" 也会命中 uXXXX 前缀规则
        Integer cp = MAP.get(glyphName);
        if (cp != null) {
            return cp;
        }
        if (glyphName.startsWith("uni")) {
            return uniChain(glyphName, 3);
        }
        if (glyphName.startsWith("u")) {
            return uniChain(glyphName, 1);
        }
        return -1;
    }

    /** uniXXXXXYYY… / uXXXXXYYY…：连续 4 位十六进制组 */
    private static int uniChain(String name, int from) {
        String hex = name.substring(from);
        if (hex.length() < 4) {
            return -1;
        }
        int n = hex.length() / 4;
        try {
            // v1 只取第一组（组合序列的其余部分忽略）
            int cp = Integer.parseInt(hex.substring(0, 4), 16);
            for (int i = 1; i < n; i++) {
                Integer.parseInt(hex.substring(i * 4, i * 4 + 4), 16); // 校验可解析
            }
            return Character.isValidCodePoint(cp) ? cp : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 槽位 → Unicode 字符串；未知返回 null */
    public static String unicodeOf(String glyphName) {
        int cp = codePointOf(glyphName);
        return cp < 0 ? null : new String(Character.toChars(cp));
    }

    private static Map<String, Integer> build() {
        Map<String, Integer> m = new HashMap<>(1024);
        // 紧凑表：名=码位十六进制
        put(m,
            // ASCII / 基础
            "space=0020,exclam=0021,quotedbl=0022,numbersign=0023,dollar=0024,percent=0025,"
            + "ampersand=0026,quotesingle=0027,parenleft=0028,parenright=0029,asterisk=002A,"
            + "plus=002B,comma=002C,hyphen=002D,period=002E,slash=002F,"
            + "zero=0030,one=0031,two=0032,three=0033,four=0034,five=0035,six=0036,seven=0037,"
            + "eight=0038,nine=0039,colon=003A,semicolon=003B,less=003C,equal=003D,"
            + "greater=003E,question=003F,at=0040,"
            + "A=0041,B=0042,C=0043,D=0044,E=0045,F=0046,G=0047,H=0048,I=0049,"
            + "J=004A,K=004B,L=004C,M=004D,N=004E,O=004F,P=0050,Q=0051,R=0052,"
            + "S=0053,T=0054,U=0055,V=0056,W=0057,X=0058,Y=0059,Z=005A,"
            + "bracketleft=005B,backslash=005C,"
            + "bracketright=005D,asciicircum=005E,underscore=005F,grave=0060,"
            + "a=0061,b=0062,c=0063,d=0064,e=0065,f=0066,g=0067,h=0068,i=0069,"
            + "j=006A,k=006B,l=006C,m=006D,n=006E,o=006F,p=0070,q=0071,r=0072,"
            + "s=0073,t=0074,u=0075,v=0076,w=0077,x=0078,y=0079,z=007A,"
            + "braceleft=007B,bar=007C,braceright=007D,asciitilde=007E,"
            // Latin-1 补充（AGL 命名）
            + "Agrave=00C0,Aacute=00C1,Acircumflex=00C2,Atilde=00C3,Adieresis=00C4,Aring=00C5,"
            + "AE=00C6,Ccedilla=00C7,Egrave=00C8,Eacute=00C9,Ecircumflex=00CA,Edieresis=00CB,"
            + "Igrave=00CC,Iacute=00CD,Icircumflex=00CE,Idieresis=00CF,Eth=00D0,Ntilde=00D1,"
            + "Ograve=00D2,Oacute=00D3,Ocircumflex=00D4,Otilde=00D5,Odieresis=00D6,multiply=00D7,"
            + "Oslash=00D8,Ugrave=00D9,Uacute=00DA,Ucircumflex=00DB,Udieresis=00DC,"
            + "Yacute=00DD,Thorn=00DE,germandbls=00DF,agrave=00E0,aacute=00E1,acircumflex=00E2,"
            + "atilde=00E3,adieresis=00E4,aring=00E5,ae=00E6,ccedilla=00E7,egrave=00E8,"
            + "eacute=00E9,ecircumflex=00EA,edieresis=00EB,igrave=00EC,iacute=00ED,"
            + "icircumflex=00EE,idieresis=00EF,eth=00F0,ntilde=00F1,ograve=00F2,oacute=00F3,"
            + "ocircumflex=00F4,otilde=00F5,odieresis=00F6,divide=00F7,oslash=00F8,ugrave=00F9,"
            + "uacute=00FA,ucircumflex=00FB,udieresis=00FC,yacute=00FD,thorn=00FE,"
            + "ydieresis=00FF,"
            // 拉丁扩展 A 常用
            + "Amacron=0100,amacron=0101,Abreve=0102,abreve=0103,Aogonek=0104,aogonek=0105,"
            + "Cacute=0106,cacute=0107,Ccircumflex=0108,ccircumflex=0109,Cdotaccent=010A,"
            + "cdotaccent=010B,Ccaron=010C,ccaron=010D,Dcaron=010E,dcaron=010F,"
            + "Emacron=0112,emacron=0113,Ebreve=0114,ebreve=0115,Edotaccent=0116,"
            + "edotaccent=0117,Eogonek=0118,eogonek=0119,Ecaron=011A,ecaron=011B,"
            + "Gcircumflex=011C,gcircumflex=011D,Gbreve=011E,gbreve=011F,"
            + "Idotaccent=0130,IJ=0132,ij=0133,Kcaron=0136,kcaron=0137,Lacute=0139,lacute=013A,"
            + "Lcaron=013D,lcaron=013E,Ldot=013F,ldot=0140,Lstroke=0141,lstroke=0142,"
            + "Nacute=0143,nacute=0144,Ncaron=0147,ncaron=0148,Eng=014A,eng=014B,"
            + "Omacron=014C,omacron=014D,Obreve=014E,obreve=014F,Ohungarumlaut=0150,"
            + "ohungarumlaut=0151,Odoubleacute=0150,Racute=0154,racute=0155,Rcaron=0158,"
            + "rcaron=0159,Sacute=015A,sacute=015B,Scircumflex=015C,scircumflex=015D,"
            + "Scedilla=015E,scedilla=015F,Scaron=0160,scaron=0161,Tcaron=0164,tcaron=0165,"
            + "Tcedilla=0162,tcedilla=0163,Uogonek=0172,uogonek=0173,Uhungarumlaut=0170,"
            + "uhungarumlaut=0171,Uring=016E,uring=016F,Ydieresis=0178,Zacute=0179,zacute=017A,"
            + "Zdotaccent=017B,zdotaccent=017C,Zcaron=017D,zcaron=017E,"
            // 标点与符号
            + "brokenbar=00A6,section=00A7,dieresis=00A8,copyright=00A9,ordfeminine=00AA,"
            + "registered=00AE,degree=00B0,plusminus=00B1,twosuperior=00B2,threesuperior=00B3,"
            + "periodcentered=00B7,onesuperior=00B9,ordmasculine=00BA,onequarter=00BC,"
            + "onehalf=00BD,threequarters=00BE,questiondown=00BF,florin=0192,"
            + "quoteleft=2018,quoteright=2019,quotesinglbase=201A,quotedblleft=201C,"
            + "quotedblright=201D,quotedblbase=201E,dagger=2020,daggerdbl=2021,bullet=2022,"
            + "ellipsis=2026,perthousand=2030,guillemotleft=00AB,guillemotright=00BB,"
            + "guilsinglleft=2039,guilsinglright=203A,endash=2013,emdash=2014,"
            + "hyphenchar=002D,minus=2212,frac18=215B,frac38=215C,frac58=215D,frac78=215E,"
            + "dotmath=22C5,currency=00A5,cent=00A2,sterling=00A3,yen=00A5,"
            + "trademark=2122,plusminusminus=2213,copyrightserif=00A9,registerserif=00AE,"
            + "trademarkserif=2122,"
            // 连字
            + "ff=FB00,fi=FB01,fl=FB02,ffi=FB03,ffl=FB04,longs=017F,"
            // 特殊
            + "dotlessi=0131,dotlessj=0237,visiblespace=2423,afii61352=2011"
        );
        put(m,
            // 希腊
            "Alpha=0391,Beta=0392,Gamma=0393,Delta=0394,Epsilon=0395,Zeta=0396,Eta=0397,"
            + "Theta=0398,Iota=0399,Kappa=039A,Lambda=039B,Mu=039C,Nu=039D,Xi=039E,"
            + "Omicron=039F,Pi=03A0,Rho=03A1,Sigma=03A3,Tau=03A4,Upsilon=03A5,Phi=03A6,"
            + "Chi=03A7,Psi=03A8,Omega=03A9,alpha=03B1,beta=03B2,gamma=03B3,delta=03B4,"
            + "epsilon=03B5,zeta=03B6,eta=03B7,theta=03B8,iota=03B9,kappa=03BA,lambda=03BB,"
            + "mu=03BC,nu=03BD,xi=03BE,omicron=03BF,pi=03C0,rho=03C1,sigmaf=03C2,sigma1=03C2,"
            + "sigma=03C3,tau=03C4,upsilon=03C5,phi=03C6,chi=03C7,psi=03C8,omega=03C9,"
            + "varTheta=03D1,phi1=03D5,omega1=03D6,pitchfork=22D4,"
            // 数学与逻辑
            + "universal=2200,existential=2203,thereexists=2203,emptyset=2205,nabla=2207,"
            + "element=2208,notelement=2209,notsubset=2284,propersubset=2282,reflexsubset=2286,"
            + "propersuperset=2283,reflexsuperset=2287,intersection=2229,union=222A,"
            + "logicaland=2227,logicalor=2228,logicalnot=00AC,turnstile=22A2,perpendicular=22A5,"
            + "angle=2220,gradient=2207,product=220F,summation=2211,integral=222B,"
            + "contourintegral=222E,radical=221A,approxequal=2248,congruent=2245,"
            + "notequal=2260,equivalence=2261,lessequal=2264,greaterequal=2265,"
            + "infinity=221E,aleph=2135,partialdiff=2202,similar=223C,therefore=2234,"
            + "because=2235,floorleft=230A,floorright=230B,ceilingleft=2308,ceilingright=2309,"
            + "angleleft=2329,angleright=232A,lozenge=25CA,triangledown=25BF," +
            "triangleright=25B9,triangleleft=25C3,arrownortheast=2197,arrowupleft=2196"
        );
        put(m,
            // 箭头
            "arrowup=2191,arrowdown=2193,arrowleft=2190,arrowright=2192,arrowboth=2194,"
            + "arrowvertex=2195,arrowdblup=21D1,arrowdbldown=21D3,"
            + "arrowdblleft=21D0,arrowdblright=21D2,arrowdblboth=21D4,carriagereturn=21B5,"
            + "uparrow=2191,downarrow=2193,leftarrow=2190,rightarrow=2192,"
            // 几何/杂项
            + "circleplus=2295,circlemultiply=2297,diamond=22C4,triangular=25B3,triangledowns=25BF,"
            + "spade=2660,heart=2665,club=2663,diamondsuit=2666,"
            // 补充拉丁扩展 B / 拉丁全角
            + "Scommaaccent=0218,scommaaccent=0219,Tcommaaccent=021A,tcommaaccent=021B,"
            + "florin=0192"
        );
        return m;
    }

    private static void put(Map<String, Integer> m, String spec) {
        for (String pair : spec.split(",")) {
            pair = pair.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq + 5 > pair.length()) {
                continue;
            }
            try {
                int cp = Integer.parseInt(pair.substring(eq + 1).trim(), 16);
                if (Character.isValidCodePoint(cp)) {
                    m.putIfAbsent(pair.substring(0, eq).trim(), cp);
                }
            } catch (NumberFormatException ignore) {
                // 非法项：跳过
            }
        }
    }
}
