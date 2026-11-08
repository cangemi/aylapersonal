package ayla.investment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Period returns use changes in value plus distributions, excluding contributions and withdrawals. */
public final class InvestmentAnalytics {
    private InvestmentAnalytics() {}
    public record Period(String key, LocalDate from, LocalDate to, long balanceCents, long netInvestedCents,
        long accumulatedGainCents, long baseCents, long depositsCents, long withdrawalsCents, long incomeCents,
        long observedGainCents, Long gainCents, BigDecimal gainPercent, boolean complete, boolean partial,
        boolean estimated) {}
    public record Performance(LocalDate trackingFrom, LocalDate lastValuationDate, PortfolioData.Rate currentRate,
        Period day, Period month, Period year, List<Period> days, List<Period> months, List<Period> years) {}
    public record Holding(String id, String name, String institution, PortfolioData.AssetType assetType,
        long balanceCents, long netInvestedCents, long gainCents, Performance performance) {}
    public record Dashboard(int year, int firstYear, LocalDate asOf, LocalDate cdiDate, List<Holding> holdings, List<String> warnings) {}

    static final class Collector {
        private final LocalDate openedOn, today;
        private final int selectedYear;
        private final List<Period> days = new ArrayList<>();
        private final Map<YearMonth,Bucket> months = new LinkedHashMap<>();
        private final Map<Integer,Bucket> years = new LinkedHashMap<>();
        private Period lastDay;
        Collector(LocalDate openedOn,LocalDate today,int selectedYear){this.openedOn=openedOn;this.today=today;this.selectedYear=selectedYear;}

        void add(LocalDate date,long before,long balance,long net,long accumulated,long gain,long deposits,long withdrawals,long income,
                 boolean startKnown,boolean endKnown,boolean estimated) {
            var day = new Bucket(date,date,before,startKnown && date.isAfter(openedOn),date.equals(openedOn));
            day.add(date,balance,net,accumulated,gain,deposits,withdrawals,income,endKnown,estimated);
            lastDay=day.period(date.toString());
            if(date.getYear()==selectedYear)days.add(lastDay);
            months.computeIfAbsent(YearMonth.from(date),key -> new Bucket(key.atDay(1),date,before,startKnown,openedOn.isAfter(key.atDay(1))))
                .add(date,balance,net,accumulated,gain,deposits,withdrawals,income,endKnown,estimated);
            years.computeIfAbsent(date.getYear(),key -> new Bucket(LocalDate.of(key,1,1),date,before,startKnown,openedOn.isAfter(LocalDate.of(key,1,1))))
                .add(date,balance,net,accumulated,gain,deposits,withdrawals,income,endKnown,estimated);
        }

        Performance finish(LocalDate lastValuation,PortfolioData.Rate rate){
            var month=months.get(YearMonth.from(today));
            var year=years.get(selectedYear);
            return new Performance(openedOn,lastValuation,rate,lastDay,month==null?null:month.period(YearMonth.from(today).toString()),
                year==null?null:year.period(Integer.toString(selectedYear)),List.copyOf(days),
                months.entrySet().stream().filter(entry->entry.getKey().getYear()==selectedYear).map(entry->entry.getValue().period(entry.getKey().toString())).toList(),
                years.entrySet().stream().map(entry->entry.getValue().period(entry.getKey().toString())).toList());
        }
    }

    private static final class Bucket {
        final LocalDate from;
        final long openingBalance;
        final boolean startKnown,partial;
        LocalDate to;
        long balance,net,accumulated,gain,deposits,withdrawals,income;
        boolean endKnown,estimated;
        Bucket(LocalDate from,LocalDate first,long openingBalance,boolean startKnown,boolean partial){
            this.from=from;this.to=first;this.openingBalance=openingBalance;this.startKnown=startKnown;this.partial=partial;
        }
        void add(LocalDate date,long balance,long net,long accumulated,long gain,long deposits,long withdrawals,long income,boolean known,boolean estimate){
            this.to=date;this.balance=balance;this.net=net;this.accumulated=accumulated;
            this.gain+=gain;this.deposits+=deposits;this.withdrawals+=withdrawals;this.income+=income;this.endKnown=known;this.estimated|=estimate;
        }
        Period period(String key){
            boolean complete=startKnown&&endKnown;
            long base=openingBalance+deposits;
            BigDecimal percent=complete&&base>0?BigDecimal.valueOf(gain).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(base),4,RoundingMode.HALF_UP):null;
            return new Period(key,from,to,balance,net,accumulated,base,deposits,withdrawals,income,gain,complete?gain:null,percent,complete,partial,estimated);
        }
    }
}
