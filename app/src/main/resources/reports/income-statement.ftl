<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <section class="income-statement-report">
    <h2 class="report-heading">${title}</h2>
    <table class="statement-table">
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
          <#assign rowType = typedRow.rowType.name()?lower_case?replace("_", "-")>
          <tr class="statement-row ${rowType}">
            <td class="label">${row[0]}</td>
            <td class="number">${row[1]!}</td>
            <td class="number percent">${row[2]!}</td>
            <td class="number">${row[3]!}</td>
            <td class="number percent">${row[4]!}</td>
            <td class="number">${row[5]!}</td>
            <td class="number percent">${row[6]!}</td>
          </tr>
        </#list>
      </tbody>
    </table>
  </section>
</@layout.page>
