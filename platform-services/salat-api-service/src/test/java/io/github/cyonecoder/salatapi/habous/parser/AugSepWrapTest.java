package io.github.cyonecoder.salatapi.habous.parser;
import static org.assertj.core.api.Assertions.assertThat;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.time.*; import java.util.*; import java.util.stream.*;
import org.junit.jupiter.api.Test;
class AugSepWrapTest {
  private String table(){
    StringBuilder sb=new StringBuilder("<html><body><table id=\"horaire\">");
    sb.append("<tr><th>الأيام</th><th>صفر</th><th>غشت / شتنبر</th><th>الصبح</th><th>الشروق</th>")
      .append("<th>الظهر</th><th>العصر</th><th>المغرب</th><th>العشاء</th></tr>");
    for(int d=15;d<=31;d++) row(sb,d);   // August 15-31
    for(int d=1;d<=13;d++) row(sb,d);    // September 1-13
    return sb.append("</table>2026</body></html>").toString();
  }
  private void row(StringBuilder sb,int d){ sb.append("<tr><td>الأربعاء</td><td>1</td><td>").append(d)
    .append("</td><td>05:00</td><td>06:30</td><td>13:30</td><td>17:00</td><td>20:00</td><td>21:30</td></tr>"); }
  @Test void hijriPageSplitsAcrossAugustAndSeptember(){
    var days=new HabousLiveHtmlParser().parse(table());
    assertThat(days.get(0).date()).isEqualTo(LocalDate.of(2026,8,15));
    assertThat(days.get(days.size()-1).date()).isEqualTo(LocalDate.of(2026,9,13));
    // strictly contiguous across the month boundary
    for(int i=1;i<days.size();i++)
      assertThat(days.get(i).date()).isEqualTo(days.get(i-1).date().plusDays(1));
    var byMonth=days.stream().collect(Collectors.groupingBy(d->YearMonth.from(d.date())));
    assertThat(byMonth.get(YearMonth.of(2026,8))).hasSize(17); // 15..31
    assertThat(byMonth.get(YearMonth.of(2026,9))).hasSize(13); // 1..13
  }
}
