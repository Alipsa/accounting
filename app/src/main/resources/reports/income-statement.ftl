<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <section class="income-statement-report">
    <h2 class="report-heading">Resultatrapport</h2>
    <table class="statement-table">
      <thead>
        <tr>
          <#list tableHeaders as header>
            <th<#if header?index == 1> class="number"</#if>>${header}</th>
          </#list>
        </tr>
      </thead>
      <tbody>
        <#list tableRows as row>
          <#assign typedRow = typedRows[row?index]>
          <#assign rowType = typedRow.rowType.name()?lower_case?replace("_", "-")>
          <tr class="statement-row ${rowType}">
            <td class="label">${row[0]}</td>
            <td class="number">${row[1]!}</td>
          </tr>
        </#list>
      </tbody>
    </table>
  </section>
</@layout.page>
