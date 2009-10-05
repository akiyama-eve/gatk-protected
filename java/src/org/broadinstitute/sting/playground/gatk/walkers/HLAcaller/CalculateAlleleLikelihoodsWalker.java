/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.broadinstitute.sting.playground.gatk.walkers.HLAcaller;
import net.sf.samtools.SAMRecord;
import org.broadinstitute.sting.gatk.walkers.*;
import org.broadinstitute.sting.utils.cmdLine.Argument;

import java.util.ArrayList;
import java.util.Hashtable;


/**
 *
 * @author shermanjia
 */
@Requires({DataSource.READS, DataSource.REFERENCE})
public class CalculateAlleleLikelihoodsWalker extends ReadWalker<Integer, Integer> {
    @Argument(fullName = "baseLikelihoods", shortName = "bl", doc = "Base likelihoods file", required = true)
    public String baseLikelihoodsFile = "";

    @Argument(fullName = "debugHLA", shortName = "debugHLA", doc = "Print debug", required = false)
    public boolean DEBUG = false;

    @Argument(fullName = "onlyfrequent", shortName = "onlyfrequent", doc = "Only consider alleles with frequency > 0.0001", required = false)
    public boolean FREQUENT = false;

    @Argument(fullName = "ethnicity", shortName = "ethnicity", doc = "Use allele frequencies for this ethnic group", required = false)
    public String ethnicity = "Caucasian";

    String CaucasianAlleleFrequencyFile = "/humgen/gsa-scr1/GSA/sjia/454_HLA/HLA/HLA_CaucasiansUSA.freq";
    String BlackAlleleFrequencyFile = "/humgen/gsa-scr1/GSA/sjia/454_HLA/HLA/HLA_BlackUSA.freq";
    String AlleleFrequencyFile;
    Hashtable AlleleFrequencies;
    
    CigarParser formatter = new CigarParser();
    double[][] baseLikelihoods;
    int[] positions;
    boolean loaded = false;

    String[] HLAnames, HLAreads;
    Integer[] HLAstartpos, HLAstoppos;
    ArrayList<String> HLAnamesAL, HLAreadsAL;
    ArrayList<Integer> HLAstartposAL, HLAstopposAL;

    public Integer reduceInit() {
        if (!loaded){
            loaded = true;
            BaseLikelihoodsFileReader baseLikelihoodsReader = new BaseLikelihoodsFileReader();
            baseLikelihoodsReader.ReadFile(baseLikelihoodsFile, false);
            baseLikelihoods = baseLikelihoodsReader.GetBaseLikelihoods();
            positions = baseLikelihoodsReader.GetPositions();

            HLAnamesAL = new ArrayList<String>();
            HLAreadsAL = new ArrayList<String>();
            HLAstartposAL = new ArrayList<Integer>();
            HLAstopposAL = new ArrayList<Integer>();

            if (ethnicity.equals("Black")){
                AlleleFrequencyFile = BlackAlleleFrequencyFile;
            }else{
                AlleleFrequencyFile = CaucasianAlleleFrequencyFile;
            }
            out.printf("INFO  Reading HLA allele frequencies ... ");
            FrequencyFileReader HLAfreqReader = new FrequencyFileReader();
            HLAfreqReader.ReadFile(AlleleFrequencyFile);
            AlleleFrequencies = HLAfreqReader.GetAlleleFrequencies();
            out.printf("Done! Frequencies for %s HLA alleles loaded.\n",AlleleFrequencies.size());

            out.printf("INFO  Reading HLA dictionary ...");


        }
        return 0;
    }

    public Integer map(char[] ref, SAMRecord read) {
        HLAnamesAL.add(read.getReadName());
        HLAreadsAL.add(formatter.FormatRead(read.getCigarString(), read.getReadString()));
        HLAstartposAL.add(read.getAlignmentStart());
        HLAstopposAL.add(read.getAlignmentEnd());
        return 1;
    }

    private int GenotypeIndex(char a, char b){
        switch(a){
            case 'A':
                switch(b){
                    case 'A': return 0;
                    case 'C': return 1;
                    case 'G': return 2;
                    case 'T': return 3;
                };
            case 'C':
                switch(b){
                    case 'A': return 1;
                    case 'C': return 4;
                    case 'G': return 5;
                    case 'T': return 6;
                };
            case 'G':
                switch(b){
                    case 'A': return 2;
                    case 'C': return 5;
                    case 'G': return 7;
                    case 'T': return 8;
                };
            case 'T':
                switch(b){
                    case 'A': return 3;
                    case 'C': return 6;
                    case 'G': return 8;
                    case 'T': return 9;
                };
            default: return -1;
        }
    }

    public Integer reduce(Integer value, Integer sum) {
        return value + sum;
    }

    public void onTraversalDone(Integer numreads) {
        out.printf("Done! %s alleles found\n", numreads);
        HLAnames = HLAnamesAL.toArray(new String[numreads]);
        HLAreads = HLAreadsAL.toArray(new String[numreads]);
        HLAstartpos = HLAstartposAL.toArray(new Integer[numreads]);
        HLAstoppos = HLAstopposAL.toArray(new Integer[numreads]);

        double[][] AlleleLikelihoods = new double[numreads][numreads];

        String name1, name2;
        double frq1, frq2;


        double minfrq = 0;
        if (FREQUENT){
            minfrq = 0.0001;
        }
        int numcombinations = 0;
        out.printf("NUM\tAllele1\tAllele2\tSSG\n");
        
        for (int i = 0; i < numreads; i++){
            name1 = HLAnames[i].substring(4);
            if (AlleleFrequencies.containsKey(name1)){
                frq1 = Double.parseDouble((String) AlleleFrequencies.get(name1).toString());
                if (frq1 > minfrq){
                    for (int j = i; j < numreads; j++){
                        name2 = HLAnames[j].substring(4);
                        if (AlleleFrequencies.containsKey(name2)){
                            frq2 = Double.parseDouble((String) AlleleFrequencies.get(name2).toString());
                            if (frq2 > minfrq){
                                if ((HLAstartpos[i] < HLAstoppos[j]) && (HLAstartpos[j] < HLAstoppos[i])){
                                    numcombinations++;
                                    AlleleLikelihoods[i][j] = CalculateLikelihood(i,j);
                                    out.printf("%s\t%s\t%s\t%.2f\n",numcombinations,name1,name2,AlleleLikelihoods[i][j]);
                                }
                            }else{
                                if (DEBUG){out.printf("%s has allele frequency%.5f\n",name2,frq2);}
                            }
                        }else{
                            if (DEBUG){out.printf("%s not found in allele frequency file\n",name2);}
                        }
                    }
                }else{
                    if (DEBUG){out.printf("%s has allele frequency%.5f\n",name1,frq1);}
                }
            }else{
                if (DEBUG){out.printf("%s not found in allele frequency file\n",name1);}
            }
        }
    }

    private double CalculateLikelihood(int a1, int a2){
        String read1 = HLAreads[a1];
        String read2 = HLAreads[a2];
        int start1 = HLAstartpos[a1];
        int start2 = HLAstartpos[a2];
        int stop1 = HLAstoppos[a1];
        int stop2 = HLAstoppos[a2];
        double likelihood = 0;
        int pos, index;
        char c1, c2;
        

        for (int i = 0; i < positions.length; i++){
            pos = positions[i];
            if (pos < stop1 && pos > start1 && pos < stop2 && pos > start2){
                index = GenotypeIndex(read1.charAt(pos-start1),read2.charAt(pos-start2));
                if (index > -1){
                    likelihood = likelihood + baseLikelihoods[i][index];
                }else{
                    /*if (DEBUG){
                        c1 = read1.charAt(pos-start1);
                        c2 = read2.charAt(pos-start2);
                        out.printf("%s\t%s\t%s\t%s\t%s\t%s\t%.2f\n",HLAnames[a1],HLAnames[a2],pos,c1,c2,index,likelihood);
                    }*/
                }
            }
        }
        return likelihood;
    }
}
