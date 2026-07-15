<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <section class="balance-sheet-report">
    <h2 class="report-heading">${title}</h2>
    <table class="statement-table balance-sheet-table">
      <colgroup>
        <col class="label-col">
        <col class="amount-col">
        <col class="movement-col">
        <col class="closing-col">
      </colgroup>
      <thead>
        <tr>
          <th>${tableHeaders[0]}</th>
          <th class="number group-start">${tableHeaders[1]}</th>
          <th class="number group-start">${tableHeaders[2]}</th>
          <th class="number closing group-start">${tableHeaders[3]}</th>
        </tr>
      </thead>
      <tbody>
        <#list tableRows as row>
          <#assign typedRow = typedRows[row?index]>
          <#assign rowClass = typedRow.rowType.name()?lower_case?replace("_", "-")>
          <tr class="statement-row ${rowClass}">
            <#if rowClass == "section-header">
              <td class="label" colspan="4">${row[0]}</td>
            <#else>
              <td class="label">
                <#if typedRow.accountNumber?? && typedRow.accountNumber?has_content>
                  <span class="account-number">${typedRow.accountNumber}</span><span class="account-name">${typedRow.accountName}</span>
                <#else>
                  ${row[0]}
                </#if>
              </td>
              <td class="number group-start">${row[1]}</td>
              <td class="number group-start">${row[2]}</td>
              <td class="number closing group-start">${row[3]}</td>
            </#if>
          </tr>
        </#list>
      </tbody>
    </table>
  </section>
</@layout.page>
