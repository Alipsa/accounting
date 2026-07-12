<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <section class="balance-sheet-report">
    <h2 class="report-heading">${title}</h2>
    <table class="statement-table balance-sheet-table">
      <thead>
        <tr>
          <#list tableHeaders as header>
            <th<#if header?index != 0> class="number"</#if>>${header}</th>
          </#list>
        </tr>
      </thead>
      <tbody>
        <#list tableRows as row>
          <#assign typedRow = typedRows[row?index]>
          <#assign rowClass = typedRow.rowType.name()?lower_case?replace("_", "-")>
          <tr class="statement-row ${rowClass}">
            <td class="label">${row[0]}</td>
            <td class="number">${row[1]}</td>
            <td class="number">${row[2]}</td>
            <td class="number">${row[3]}</td>
          </tr>
        </#list>
      </tbody>
    </table>
  </section>
</@layout.page>
